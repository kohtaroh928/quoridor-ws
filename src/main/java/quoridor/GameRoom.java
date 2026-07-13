package quoridor;

import org.java_websocket.WebSocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GameRoom {

    // 全ルーム共有のタイマー/AI実行スレッド
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "game-room-scheduler");
                t.setDaemon(true);
                return t;
            });

    private static final CharacterType[] AI_CHARACTERS = {
            CharacterType.BREAKER, CharacterType.ACROBAT, CharacterType.RUNNER,
            CharacterType.BUILDER, CharacterType.TRAPPER
    };

    // 座席: 人間(WebSocket)かAI(難易度付き)のどちらか
    public static class Seat {
        WebSocket conn;
        boolean ai;
        int aiDifficulty;
        CharacterType character = CharacterType.NONE;
        String name = "";
        int avatarId = -1;

        public static Seat human(WebSocket conn, CharacterType character, String name, int avatarId) {
            Seat s = new Seat();
            s.conn = conn;
            s.character = character == null ? CharacterType.NONE : character;
            s.name = name == null ? "" : name;
            s.avatarId = avatarId;
            return s;
        }

        public static Seat aiSeat(int difficulty) {
            Seat s = new Seat();
            s.ai = true;
            s.aiDifficulty = Math.max(1, Math.min(3, difficulty));
            return s;
        }
    }

    private final Seat[] seats;
    private final Board board;
    private boolean characterMode;
    private boolean obstacleMode;
    private boolean simultaneousMode;
    private boolean publicRoom; // trueなら観戦一覧に表示される(ロビー中にホストが変更できる)
    private int timeLimit; // 1ターンの秒数(0=無制限)。ロビー中はホストが変更できる
    private int currentPlayer = 1;
    private boolean gameOver = false;
    private boolean dissolved = false;
    private boolean started = false; // trueになるまではロビー(準備待ち)状態
    private final boolean[] readyFlags;
    private final boolean[] rematchRequested;
    private final boolean[] returnToLobbyRequested;
    private final List<WebSocket> spectators = new ArrayList<>();

    // ターンごとに増える通し番号。古いタイマー/AIタスクの誤発火を防ぐ
    private int turnSerial = 0;
    private int scheduledSerial = -1;
    private ScheduledFuture<?> pendingTask;
    private final List<ScheduledFuture<?>> simultaneousTasks = new ArrayList<>();
    private final SimultaneousRound.Action[] roundActions = new SimultaneousRound.Action[2];
    private final boolean[] waitedLastRound = new boolean[2];
    private int roundNumber = 1;

    public GameRoom(List<Seat> seatList, boolean characterMode, boolean obstacleMode,
                    boolean simultaneousMode, int timeLimit) {
        this.seats = seatList.toArray(new Seat[0]);
        this.board = new Board(this.seats.length);
        this.simultaneousMode = simultaneousMode && this.seats.length == 2;
        this.characterMode = characterMode && !this.simultaneousMode;
        this.obstacleMode = obstacleMode;
        this.timeLimit = timeLimit;
        this.rematchRequested = new boolean[this.seats.length];
        this.returnToLobbyRequested = new boolean[this.seats.length];
        this.readyFlags = new boolean[this.seats.length];

        if (this.characterMode) {
            Random random = new Random();
            for (int i = 0; i < this.seats.length; i++) {
                Seat seat = this.seats[i];
                if (seat.ai || seat.character == CharacterType.NONE) {
                    seat.character = AI_CHARACTERS[random.nextInt(AI_CHARACTERS.length)];
                }
                board.getPlayer(i + 1).setCharacter(seat.character);
            }
        }

        if (this.obstacleMode) {
            GameLogic.placeObstacleWalls(board, new Random());
        }
    }

    // 席が揃った直後に呼ぶ。まだ対局は開始せず、ロビー(準備待ち)状態にして
    // 全員のプロフィール・準備状況を通知する
    public synchronized void enterLobby() {
        broadcastLobby();
        System.out.println("Room entered lobby. (" + seats.length + " players, "
                + humanCount() + " humans, timeLimit=" + timeLimit + ")");
    }

    private synchronized void startGame() {
        started = true;
        for (int i = 0; i < seats.length; i++) {
            send(seats[i].conn, Protocol.gameStart(i + 1));
        }
        sendBoardUpdate();
        if (simultaneousMode) scheduleSimultaneousRound(); else scheduleTurn();
        System.out.println("Game started! (" + seats.length + " players, "
                + humanCount() + " humans, timeLimit=" + timeLimit + ")");
    }

    private void handleReady(int playerId) {
        if (started || dissolved) return;
        Seat seat = seats[playerId - 1];
        if (seat.ai && playerId != 1) return;
        if (seat.ai) {
            readyFlags[playerId - 1] = true;
            if (allHumanSeatsReady()) startGame();
            else broadcastLobby();
            return;
        }
        if (characterMode && seat.character == CharacterType.NONE) {
            send(seat.conn, Protocol.error("先にキャラクターを選んでください"));
            return;
        }
        readyFlags[playerId - 1] = true;
        System.out.println("Player " + playerId + " is ready.");

        if (allHumanSeatsReady()) {
            startGame();
        } else {
            broadcastLobby();
        }
    }

    private void broadcastLobby() {
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].conn != null) {
                send(seats[i].conn, Protocol.lobbyUpdate(seats, readyFlags, characterMode, obstacleMode,
                        simultaneousMode, timeLimit, seats.length, i + 1, publicRoom));
            }
        }
    }

    private boolean allHumanSeatsReady() {
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].ai) continue; // AI席は自動で準備完了扱い
            if (seats[i].conn == null || !readyFlags[i]) return false;
        }
        return true;
    }

    // --- lobby rule / seat configuration (ホストが対局準備ロビーで行う設定変更) ---

    // ロビー内で参加者本人が、自分のキャラクターを選ぶ/選び直す
    private void handleSetCharacterInLobby(int playerId, String raw) {
        if (started || dissolved || !characterMode) return;
        Seat seat = seats[playerId - 1];
        if (seat.ai || raw == null) return;
        CharacterType character;
        try {
            character = CharacterType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (character == CharacterType.NONE) return;
        seat.character = character;
        board.getPlayer(playerId).setCharacter(character);
        System.out.println("Player " + playerId + " picked character " + character);
        broadcastLobby();
    }

    // ホスト(P1)のみ: 各席をAI(強さ指定)⇔オンライン参加待ちに切り替える。
    // P1は接続を管理用に残したまま、席だけAIとして扱える
    private void handleSetSeatMode(int playerId, java.util.Map<String, Object> data) {
        if (started || dissolved || playerId != 1) return;
        Object rawSeat = data.get("seat");
        if (!(rawSeat instanceof Number)) return;
        int seatIdx = ((Number) rawSeat).intValue() - 1;
        if (seatIdx < 0 || seatIdx >= seats.length) return;
        boolean hostSeat = seatIdx == 0;

        Seat seat = seats[seatIdx];
        if (!hostSeat && !seat.ai && seat.conn != null) return; // 既に人間が参加済みの席は変更できない

        String mode = (String) data.get("mode");
        Random random = new Random();
        if ("AI".equals(mode)) {
            int difficulty = data.get("difficulty") instanceof Number
                    ? ((Number) data.get("difficulty")).intValue() : 2;
            seat.ai = true;
            seat.aiDifficulty = Math.max(1, Math.min(3, difficulty));
            if (!hostSeat) seat.conn = null;
            if (characterMode) seat.character = AI_CHARACTERS[random.nextInt(AI_CHARACTERS.length)];
        } else if ("OPEN".equals(mode)) {
            seat.ai = false;
            if (!hostSeat) {
                seat.conn = null;
                seat.name = "";
                seat.avatarId = -1;
            }
            seat.character = CharacterType.NONE;
        } else {
            return;
        }
        readyFlags[seatIdx] = false;
        board.getPlayer(seatIdx + 1).setCharacter(seat.character);
        System.out.println("Seat " + (seatIdx + 1) + " set to " + mode);
        broadcastLobby();
    }

    // ホスト(P1)のみ: キャラクター/障害物/同時対戦モード・時間制限・公開設定を変更する
    private void handleUpdateRoomSettings(int playerId, java.util.Map<String, Object> data) {
        if (started || dissolved || playerId != 1) return;

        boolean newCharacterMode = Boolean.TRUE.equals(data.get("characterMode"));
        boolean newObstacleMode = Boolean.TRUE.equals(data.get("obstacleMode"));
        boolean newSimultaneousMode = seats.length == 2 && Boolean.TRUE.equals(data.get("simultaneousMode"));
        if (newSimultaneousMode) newCharacterMode = false;
        if (newCharacterMode) newSimultaneousMode = false;
        boolean newPublicRoom = Boolean.TRUE.equals(data.get("publicRoom"));
        Object rawLimit = data.get("timeLimit");
        int newTimeLimit = rawLimit instanceof Number
                ? Math.max(0, Math.min(600, ((Number) rawLimit).intValue())) : timeLimit;

        timeLimit = newTimeLimit;
        publicRoom = newPublicRoom;

        if (newCharacterMode != characterMode) {
            characterMode = newCharacterMode;
            reassignCharacters();
        }
        simultaneousMode = newSimultaneousMode;
        if (newObstacleMode != obstacleMode) {
            obstacleMode = newObstacleMode;
            board.reset(); // 対局開始前なので、盤面(壁配置)を作り直しても問題ない
            if (obstacleMode) GameLogic.placeObstacleWalls(board, new Random());
        }
        System.out.println("Room settings updated: characterMode=" + characterMode
                + ", obstacleMode=" + obstacleMode + ", simultaneousMode=" + simultaneousMode
                + ", timeLimit=" + timeLimit);
        broadcastLobby();
    }

    // characterModeの切り替え時に、AI席へはランダムでキャラクターを割り当て、
    // 人間の席は選択待ち(NONE)に戻す
    private void reassignCharacters() {
        Random random = new Random();
        for (int i = 0; i < seats.length; i++) {
            Seat seat = seats[i];
            if (!characterMode) {
                seat.character = CharacterType.NONE;
            } else if (seat.ai) {
                seat.character = AI_CHARACTERS[random.nextInt(AI_CHARACTERS.length)];
            } else {
                seat.character = CharacterType.NONE;
            }
            board.getPlayer(i + 1).setCharacter(seat.character);
        }
    }

    // --- late join: ホストが開けたオンライン参加待ちの席に、対局開始前なら参加できる ---

    public synchronized boolean canJoin() {
        if (started || dissolved) return false;
        for (Seat seat : seats) {
            if (!seat.ai && seat.conn == null) return true;
        }
        return false;
    }

    // 空いている席に参加者をアタッチする。成功したら座席番号(1始まり)、できなければ-1を返す
    public synchronized int attachHuman(WebSocket conn, String name, int avatarId) {
        for (int i = 0; i < seats.length; i++) {
            Seat seat = seats[i];
            if (!seat.ai && seat.conn == null) {
                seat.conn = conn;
                seat.name = name == null ? "" : name;
                seat.avatarId = avatarId;
                seat.character = CharacterType.NONE;
                return i + 1;
            }
        }
        return -1;
    }

    public synchronized void broadcastLobbyUpdate() {
        broadcastLobby();
    }

    public boolean getCharacterMode() { return characterMode; }
    public boolean getObstacleMode() { return obstacleMode; }
    public boolean getSimultaneousMode() { return simultaneousMode; }
    public int getTimeLimit() { return timeLimit; }
    public int getPlayerCount() { return seats.length; }

    public synchronized void onMessage(WebSocket conn, String message) {
        int playerId = seatOf(conn);
        if (playerId <= 0) return;

        try {
            java.util.Map<String, Object> data = Protocol.parseJson(message);
            String type = (String) data.get("type");

            if ("READY".equals(type)) {
                handleReady(playerId);
                return;
            }
            if ("SET_CHARACTER".equals(type)) {
                handleSetCharacterInLobby(playerId, (String) data.get("character"));
                return;
            }
            if ("SET_SEAT_MODE".equals(type)) {
                handleSetSeatMode(playerId, data);
                return;
            }
            if ("UPDATE_ROOM_SETTINGS".equals(type)) {
                handleUpdateRoomSettings(playerId, data);
                return;
            }

            if (!started) return; // ロビー中は上記のロビー操作メッセージ以外を無視する

            if ("REMATCH".equals(type)) {
                handleRematch(playerId);
                return;
            }
            if ("RETURN_TO_LOBBY".equals(type)) {
                handleReturnToLobby(playerId);
                return;
            }

            if (gameOver) return;

            if ("SURRENDER".equals(type)) {
                if (seats.length == 2) {
                    int winner = (playerId == 1) ? 2 : 1;
                    broadcast(Protocol.gameEnd(winner));
                } else {
                    broadcastExcept(conn, Protocol.error("プレイヤーが降参したため対戦を終了します"));
                    broadcast(Protocol.gameEnd(0));
                }
                endGame();
                System.out.println("Player " + playerId + " surrendered.");
                return;
            }

            if (simultaneousMode) {
                Protocol.ClientMessage msg = Protocol.parseClientMessage(message);
                handleSimultaneousAction(playerId, msg);
                return;
            }

            if (playerId != currentPlayer) {
                send(conn, Protocol.error("Not your turn"));
                return;
            }

            Protocol.ClientMessage msg = Protocol.parseClientMessage(message);
            switch (msg.type) {
                case "MOVE":
                    handleMove(playerId, msg.toX, msg.toY);
                    break;
                case "PLACE_WALL":
                    handlePlaceWall(playerId, msg.wallX, msg.wallY, msg.wallDirection);
                    break;
                case "USE_SKILL":
                    handleUseSkill(playerId, msg);
                    break;
                case "PLACE_TRAP":
                    handlePlaceTrap(playerId, msg.toX, msg.toY);
                    break;
                default:
                    send(conn, Protocol.error("Unknown action"));
            }
            scheduleTurn();
        } catch (Exception e) {
            send(conn, Protocol.error("Invalid message: " + e.getMessage()));
        }
    }

    private void handleSimultaneousAction(int playerId, Protocol.ClientMessage msg) {
        int index = playerId - 1;
        if (roundActions[index] != null) {
            send(getSocket(playerId), Protocol.error("このラウンドの行動は確定済みです"));
            return;
        }

        SimultaneousRound.Action action;
        if ("MOVE".equals(msg.type)) {
            action = SimultaneousRound.Action.move(msg.toX, msg.toY);
        } else if ("PLACE_WALL".equals(msg.type)) {
            action = SimultaneousRound.Action.wall(new Wall(msg.wallX, msg.wallY, msg.wallDirection));
        } else if ("WAIT".equals(msg.type)) {
            action = SimultaneousRound.Action.waitAction();
        } else {
            send(getSocket(playerId), Protocol.error("同時対戦では通常移動・通常壁・待機のみ選べます"));
            return;
        }

        if (!SimultaneousRound.isValidInput(board, playerId, action, !waitedLastRound[index])) {
            send(getSocket(playerId), Protocol.error("その行動は選択できません"));
            return;
        }
        roundActions[index] = action;
        send(getSocket(playerId), Protocol.actionAccepted(roundNumber));
        tryResolveSimultaneousRound();
    }

    private void tryResolveSimultaneousRound() {
        if (roundActions[0] == null || roundActions[1] == null || gameOver) return;
        cancelPendingTask();
        SimultaneousRound.Action[] resolvedActions = {roundActions[0], roundActions[1]};
        SimultaneousRound.Outcome outcome = SimultaneousRound.resolve(board, resolvedActions[0], resolvedActions[1]);
        waitedLastRound[0] = resolvedActions[0].kind == SimultaneousRound.Kind.WAIT;
        waitedLastRound[1] = resolvedActions[1].kind == SimultaneousRound.Kind.WAIT;

        sendBoardUpdate();
        broadcast(Protocol.roundResult(roundNumber, resolvedActions, outcome));
        if (outcome.winner >= 0) {
            broadcast(Protocol.gameEnd(outcome.winner, outcome.winner == 0));
            endGame();
            return;
        }

        roundActions[0] = null;
        roundActions[1] = null;
        roundNumber++;
        turnSerial++;
        sendBoardUpdate();
        scheduleSimultaneousRound();
    }

    private void handleMove(int playerId, int toX, int toY) {
        if (!GameLogic.isValidMove(board, playerId, toX, toY)) {
            send(getSocket(playerId), Protocol.error("Invalid move"));
            return;
        }
        movePlayerTo(playerId, toX, toY);
        System.out.println("Player " + playerId + " moved to (" + toX + "," + toY + ")");

        finishTurnAfterMove(playerId);
    }

    private void handlePlaceWall(int playerId, int x, int y, Wall.Direction dir) {
        Wall wall = new Wall(x, y, dir);
        if (!GameLogic.isValidWallPlacement(board, playerId, wall)) {
            send(getSocket(playerId), Protocol.error("Invalid wall"));
            return;
        }
        board.addWall(wall);
        board.getPlayer(playerId).useWall();
        board.getPlayer(playerId).setCannotMoveNextTurn(false);
        System.out.println("Player " + playerId + " placed wall " + wall);
        nextTurn();
        sendBoardUpdate();
    }

    private void handleUseSkill(int playerId, Protocol.ClientMessage msg) {
        if (!characterMode) {
            send(getSocket(playerId), Protocol.error("Skills are not available in normal mode"));
            return;
        }

        switch (msg.skill) {
            case "RUNNER_MOVE":
                handleRunnerMove(playerId, msg.toX, msg.toY);
                break;
            case "ACROBAT_MOVE":
                handleAcrobatMove(playerId, msg.toX, msg.toY);
                break;
            case "BREAK_WALL":
                handleBreakWall(playerId, msg.wallX, msg.wallY, msg.wallDirection);
                break;
            case "PLACE_MINI_WALLS":
                handlePlaceMiniWalls(playerId, msg.miniWalls);
                break;
            default:
                send(getSocket(playerId), Protocol.error("Unknown skill"));
        }
    }

    private void handleRunnerMove(int playerId, int toX, int toY) {
        if (!GameLogic.isValidRunnerMove(board, playerId, toX, toY)) {
            send(getSocket(playerId), Protocol.error("Invalid runner move"));
            return;
        }

        Player player = board.getPlayer(playerId);
        int dx = Integer.compare(toX - player.getX(), 0);
        int dy = Integer.compare(toY - player.getY(), 0);
        int stepX = player.getX() + dx;
        int stepY = player.getY() + dy;

        player.useSkill();
        boolean stoppedByTrap = movePlayerTo(playerId, stepX, stepY);
        if (!stoppedByTrap) {
            movePlayerTo(playerId, toX, toY);
        }

        System.out.println("Player " + playerId + " used RUNNER_MOVE");
        finishTurnAfterMove(playerId);
    }

    private void handleAcrobatMove(int playerId, int toX, int toY) {
        if (!GameLogic.isValidAcrobatMove(board, playerId, toX, toY)) {
            send(getSocket(playerId), Protocol.error("Invalid acrobat move"));
            return;
        }

        board.getPlayer(playerId).useSkill();
        movePlayerTo(playerId, toX, toY);
        System.out.println("Player " + playerId + " used ACROBAT_MOVE");
        finishTurnAfterMove(playerId);
    }

    private void handleBreakWall(int playerId, int x, int y, Wall.Direction dir) {
        Wall target = new Wall(x, y, dir);
        if (!GameLogic.canBreakWall(board, playerId, target)) {
            send(getSocket(playerId), Protocol.error("Invalid wall break"));
            return;
        }

        board.removeWall(target);
        board.addBrokenWall(target);
        board.getPlayer(playerId).useSkill();
        board.getPlayer(playerId).setCannotMoveNextTurn(false);
        System.out.println("Player " + playerId + " broke wall " + target);
        nextTurn();
        sendBoardUpdate();
    }

    private void handlePlaceMiniWalls(int playerId, List<MiniWall> miniWalls) {
        if (!GameLogic.isValidMiniWallPlacements(board, playerId, miniWalls)) {
            send(getSocket(playerId), Protocol.error("Invalid mini wall placement"));
            return;
        }

        for (MiniWall wall : miniWalls) board.addMiniWall(wall);
        board.getPlayer(playerId).useMiniWalls(miniWalls.size());
        board.getPlayer(playerId).useSkill();
        board.getPlayer(playerId).setCannotMoveNextTurn(false);
        System.out.println("Player " + playerId + " placed " + miniWalls.size() + " mini wall(s)");
        nextTurn();
        sendBoardUpdate();
    }

    private void handlePlaceTrap(int playerId, int x, int y) {
        if (!characterMode) {
            send(getSocket(playerId), Protocol.error("Traps are not available in normal mode"));
            return;
        }
        if (!GameLogic.isValidTrapPlacement(board, playerId, x, y)) {
            send(getSocket(playerId), Protocol.error("Invalid trap placement"));
            return;
        }

        board.addTrap(new Trap(x, y, playerId));
        board.getPlayer(playerId).useTrap();
        System.out.println("Player " + playerId + " placed trap at (" + x + "," + y + ")");
        sendBoardUpdate();
    }

    private boolean movePlayerTo(int playerId, int x, int y) {
        Player player = board.getPlayer(playerId);
        player.setPosition(x, y);

        Trap trap = board.findTriggeredTrap(playerId, x, y);
        if (trap == null) return false;

        trap.deactivate();
        player.setCannotMoveNextTurn(true);
        System.out.println("Player " + playerId + " triggered a trap at (" + x + "," + y + ")");
        return true;
    }

    private void finishTurnAfterMove(int playerId) {
        if (GameLogic.checkWin(board, playerId)) {
            sendBoardUpdate();
            broadcast(Protocol.gameEnd(playerId));
            endGame();
            System.out.println("Player " + playerId + " wins!");
            return;
        }
        nextTurn();
        sendBoardUpdate();
    }

    // --- turn timer / AI ---

    private void scheduleSimultaneousRound() {
        if (!simultaneousMode || gameOver || dissolved) return;
        cancelPendingTask();
        final int serial = turnSerial;
        for (int i = 0; i < 2; i++) {
            if (!seats[i].ai || roundActions[i] != null) continue;
            final int playerId = i + 1;
            simultaneousTasks.add(SCHEDULER.schedule(() -> runSimultaneousAi(serial, playerId),
                    600, TimeUnit.MILLISECONDS));
        }
        if (timeLimit > 0) {
            pendingTask = SCHEDULER.schedule(() -> onSimultaneousTimeout(serial),
                    timeLimit, TimeUnit.SECONDS);
        }
    }

    private void runSimultaneousAi(int serial, int playerId) {
        synchronized (this) {
            if (gameOver || dissolved || serial != turnSerial || roundActions[playerId - 1] != null) return;
            AIEngine.State state = AIEngine.fromBoard(board, false);
            AIEngine.Move move = AIEngine.getBestMove(state, playerId, seats[playerId - 1].aiDifficulty);
            SimultaneousRound.Action action = toSimultaneousAiAction(move);
            if (action == null || !SimultaneousRound.isValidInput(board, playerId, action,
                    !waitedLastRound[playerId - 1])) {
                action = timeoutAction(playerId);
            }
            roundActions[playerId - 1] = action;
            tryResolveSimultaneousRound();
        }
    }

    private SimultaneousRound.Action toSimultaneousAiAction(AIEngine.Move move) {
        if (move == null) return null;
        if (move.kind == AIEngine.Move.CELL) return SimultaneousRound.Action.move(move.x, move.y);
        if (move.kind == AIEngine.Move.WALL) {
            Wall.Direction direction = move.horizontal ? Wall.Direction.HORIZONTAL : Wall.Direction.VERTICAL;
            return SimultaneousRound.Action.wall(new Wall(move.x, move.y, direction));
        }
        return null;
    }

    private void onSimultaneousTimeout(int serial) {
        synchronized (this) {
            if (gameOver || dissolved || serial != turnSerial) return;
            for (int i = 0; i < 2; i++) {
                if (roundActions[i] == null) {
                    roundActions[i] = timeoutAction(i + 1);
                    send(getSocket(i + 1), Protocol.notice("時間切れのため行動が自動選択されました"));
                }
            }
            tryResolveSimultaneousRound();
        }
    }

    private SimultaneousRound.Action timeoutAction(int playerId) {
        int index = playerId - 1;
        if (!waitedLastRound[index]) return SimultaneousRound.Action.waitAction();

        List<SimultaneousRound.Action> moves = new ArrayList<>();
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                SimultaneousRound.Action action = SimultaneousRound.Action.move(x, y);
                if (SimultaneousRound.isValidInput(board, playerId, action, false)) moves.add(action);
            }
        }
        if (!moves.isEmpty()) return moves.get(new Random().nextInt(moves.size()));

        for (int x = 0; x < Board.WALL_MAX; x++) {
            for (int y = 0; y < Board.WALL_MAX; y++) {
                for (Wall.Direction direction : Wall.Direction.values()) {
                    SimultaneousRound.Action action = SimultaneousRound.Action.wall(new Wall(x, y, direction));
                    if (SimultaneousRound.isValidInput(board, playerId, action, false)) return action;
                }
            }
        }
        return SimultaneousRound.Action.waitAction();
    }

    // 現在の手番に応じて、AIの一手かターンタイムアウトを予約する。
    // 同じターンに対しては一度しか予約しない(トラップ設置は手番が続くため再予約されない)
    private void scheduleTurn() {
        if (simultaneousMode) return;
        if (gameOver || dissolved) {
            cancelPendingTask();
            return;
        }
        if (scheduledSerial == turnSerial) return;
        scheduledSerial = turnSerial;
        cancelPendingTask();

        final int serial = turnSerial;
        Seat seat = seats[currentPlayer - 1];
        if (seat.ai) {
            pendingTask = SCHEDULER.schedule(() -> runAiTurn(serial), 600, TimeUnit.MILLISECONDS);
        } else if (timeLimit > 0) {
            pendingTask = SCHEDULER.schedule(() -> onTurnTimeout(serial), timeLimit, TimeUnit.SECONDS);
        }
    }

    private void runAiTurn(int serial) {
        synchronized (this) {
            if (gameOver || dissolved || serial != turnSerial) return;
            int p = currentPlayer;
            if (!seats[p - 1].ai) return;

            // トラップ設置は手番が続くため、最大数回まで続けて指す
            for (int guard = 0; guard < 3 && !gameOver && currentPlayer == p && turnSerial == serial; guard++) {
                AIEngine.State state = AIEngine.fromBoard(board, characterMode);
                AIEngine.Move move = AIEngine.getBestMove(state, p, seats[p - 1].aiDifficulty);
                if (!applyAiMove(p, move)) {
                    // 想定外の不正手(あるいは合法手なし)はスキップして進行を止めない
                    board.getPlayer(p).setCannotMoveNextTurn(false);
                    nextTurn();
                    sendBoardUpdate();
                    break;
                }
            }
            scheduleTurn();
        }
    }

    private boolean applyAiMove(int p, AIEngine.Move m) {
        switch (m.kind) {
            case AIEngine.Move.CELL:
                if (!GameLogic.isValidMove(board, p, m.x, m.y)) return false;
                handleMove(p, m.x, m.y);
                return true;
            case AIEngine.Move.WALL: {
                Wall wall = new Wall(m.x, m.y, m.horizontal ? Wall.Direction.HORIZONTAL : Wall.Direction.VERTICAL);
                if (!GameLogic.isValidWallPlacement(board, p, wall)) return false;
                handlePlaceWall(p, m.x, m.y, wall.getDirection());
                return true;
            }
            case AIEngine.Move.SKILL_MOVE:
                if ("RUNNER_MOVE".equals(m.skill)) {
                    if (!GameLogic.isValidRunnerMove(board, p, m.x, m.y)) return false;
                    handleRunnerMove(p, m.x, m.y);
                } else {
                    if (!GameLogic.isValidAcrobatMove(board, p, m.x, m.y)) return false;
                    handleAcrobatMove(p, m.x, m.y);
                }
                return true;
            case AIEngine.Move.BREAK_WALL: {
                Wall target = new Wall(m.x, m.y, m.horizontal ? Wall.Direction.HORIZONTAL : Wall.Direction.VERTICAL);
                if (!GameLogic.canBreakWall(board, p, target)) return false;
                handleBreakWall(p, m.x, m.y, target.getDirection());
                return true;
            }
            case AIEngine.Move.MINI_WALLS: {
                List<MiniWall> walls = new ArrayList<>();
                for (int[] w : m.miniWalls) {
                    walls.add(new MiniWall(w[0], w[1],
                            w[2] == 1 ? MiniWall.Direction.HORIZONTAL : MiniWall.Direction.VERTICAL));
                }
                if (!GameLogic.isValidMiniWallPlacements(board, p, walls)) return false;
                handlePlaceMiniWalls(p, walls);
                return true;
            }
            case AIEngine.Move.TRAP:
                if (!GameLogic.isValidTrapPlacement(board, p, m.x, m.y)) return false;
                handlePlaceTrap(p, m.x, m.y);
                return true;
            default:
                return false;
        }
    }

    private void onTurnTimeout(int serial) {
        synchronized (this) {
            if (gameOver || dissolved || serial != turnSerial) return;
            int p = currentPlayer;
            // 時間切れはターンスキップ。トラップの移動禁止も1ターン消費したとみなして解除する
            board.getPlayer(p).setCannotMoveNextTurn(false);
            broadcast(Protocol.notice("P" + p + " は時間切れのためターンをスキップしました"));
            System.out.println("Player " + p + " timed out. Turn skipped.");
            nextTurn();
            sendBoardUpdate();
            scheduleTurn();
        }
    }

    private void endGame() {
        gameOver = true;
        cancelPendingTask();
    }

    private void cancelPendingTask() {
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
        for (ScheduledFuture<?> task : simultaneousTasks) task.cancel(false);
        simultaneousTasks.clear();
    }

    // --- disconnect ---

    public synchronized void onPlayerDisconnected(WebSocket conn) {
        int playerId = seatOf(conn);
        if (playerId <= 0) return;
        Seat seat = seats[playerId - 1];
        seat.conn = null;

        if (!started) {
            // ロビー(準備待ち)中の離脱は対局が始まっていないため、そのままルームを解散する
            dissolved = true;
            cancelPendingTask();
            for (Seat s : seats) {
                if (s.conn != null && s.conn.isOpen()) send(s.conn, Protocol.roomClosed());
            }
            for (WebSocket sp : spectators) send(sp, Protocol.roomClosed());
            return;
        }

        if (!gameOver) {
            if (seats.length == 2) {
                // 2人戦: 従来通り残った側の勝ち
                endGame();
                dissolved = true;
                Seat other = seats[playerId == 1 ? 1 : 0];
                if (other.conn != null && other.conn.isOpen()) {
                    send(other.conn, Protocol.error("Opponent disconnected"));
                    send(other.conn, Protocol.gameEnd(playerId == 1 ? 2 : 1));
                }
                for (WebSocket sp : spectators) send(sp, Protocol.gameEnd(playerId == 1 ? 2 : 1));
                return;
            }

            // 4人戦: 切断したプレイヤーはAI(難易度:強)と交代して対局を続ける
            if (humanCount() == 0) {
                endGame();
                dissolved = true;
                return;
            }
            seat.ai = true;
            seat.aiDifficulty = 3;
            broadcast(Protocol.notice("P" + playerId + " の通信が切れたため、AI(強)が引き継ぎます"));
            System.out.println("Player " + playerId + " disconnected. AI takes over.");
            sendBoardUpdate();
            scheduledSerial = -1; // 切断した席の手番なら即AIを起動する
            scheduleTurn();
            return;
        }

        // ゲーム終了後(再戦待ち中を含む)に相手が退出した場合、待機側に通知して解散する
        dissolved = true;
        cancelPendingTask();
        for (Seat s : seats) {
            if (s.conn != null && s.conn.isOpen()) send(s.conn, Protocol.roomClosed());
        }
        for (WebSocket sp : spectators) send(sp, Protocol.roomClosed());
    }

    // --- rematch ---

    private void handleRematch(int playerId) {
        if (!gameOver || dissolved) return;
        rematchRequested[playerId - 1] = true;

        boolean allRequested = true;
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].ai) continue; // AI席は自動で合意
            if (seats[i].conn == null || !rematchRequested[i]) { allRequested = false; break; }
        }

        if (allRequested) {
            startRematch();
        } else {
            for (Seat s : seats) {
                if (!s.ai && s.conn != null && s.conn != getSocket(playerId)) {
                    send(s.conn, Protocol.rematchRequested());
                }
            }
        }
    }

    private void startRematch() {
        board.reset();
        if (obstacleMode) {
            GameLogic.placeObstacleWalls(board, new Random());
        }
        currentPlayer = 1;
        roundActions[0] = null;
        roundActions[1] = null;
        waitedLastRound[0] = false;
        waitedLastRound[1] = false;
        roundNumber = 1;
        turnSerial++;
        gameOver = false;
        for (int i = 0; i < rematchRequested.length; i++) rematchRequested[i] = false;
        for (int i = 0; i < seats.length; i++) {
            send(seats[i].conn, Protocol.gameStart(i + 1));
        }
        sendBoardUpdate();
        if (simultaneousMode) scheduleSimultaneousRound(); else scheduleTurn();
        System.out.println("Rematch started!");
    }

    // --- return to lobby ---

    private void handleReturnToLobby(int playerId) {
        if (!gameOver || dissolved) return;
        returnToLobbyRequested[playerId - 1] = true;

        boolean allRequested = true;
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].ai) continue; // AI席は自動で合意
            if (seats[i].conn == null || !returnToLobbyRequested[i]) { allRequested = false; break; }
        }

        if (allRequested) {
            returnToLobby();
        } else {
            for (Seat s : seats) {
                if (!s.ai && s.conn != null && s.conn != getSocket(playerId)) {
                    send(s.conn, Protocol.returnToLobbyRequested());
                }
            }
        }
    }

    private void returnToLobby() {
        cancelPendingTask();
        board.reset();
        if (obstacleMode) {
            GameLogic.placeObstacleWalls(board, new Random());
        }
        currentPlayer = 1;
        roundActions[0] = null;
        roundActions[1] = null;
        waitedLastRound[0] = false;
        waitedLastRound[1] = false;
        roundNumber = 1;
        turnSerial++;
        gameOver = false;
        started = false;
        for (int i = 0; i < rematchRequested.length; i++) rematchRequested[i] = false;
        for (int i = 0; i < returnToLobbyRequested.length; i++) returnToLobbyRequested[i] = false;
        for (int i = 0; i < readyFlags.length; i++) readyFlags[i] = false;
        broadcastLobby();
        // 観戦は対局中のルームのみが対象のため、ロビーに戻ったら観戦者は退出させる
        for (WebSocket sp : spectators) send(sp, Protocol.roomClosed());
        spectators.clear();
        System.out.println("Room returned to lobby.");
    }

    // --- helpers ---

    public synchronized boolean isDissolved() { return dissolved; }

    public boolean isGameOver() { return gameOver; }

    public synchronized List<WebSocket> getHumanSockets() {
        List<WebSocket> result = new ArrayList<>();
        for (Seat s : seats) {
            if (!s.ai && s.conn != null) result.add(s.conn);
        }
        return result;
    }

    public List<WebSocket> getOtherPlayers(WebSocket conn) {
        List<WebSocket> others = new ArrayList<>();
        for (Seat s : seats) {
            if (s.conn != null && s.conn != conn) others.add(s.conn);
        }
        return others;
    }

    private int humanCount() {
        int n = 0;
        for (Seat s : seats) {
            if (!s.ai && s.conn != null) n++;
        }
        return n;
    }

    private int seatOf(WebSocket conn) {
        if (conn == null) return -1;
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].conn == conn) return i + 1;
        }
        return -1;
    }

    private void nextTurn() {
        currentPlayer = currentPlayer % board.getPlayerCount() + 1;
        turnSerial++;
    }

    private void broadcast(String json) {
        for (Seat s : seats) send(s.conn, json);
        for (WebSocket sp : spectators) send(sp, json);
    }

    private void broadcastExcept(WebSocket exclude, String json) {
        for (Seat s : seats) {
            if (s.conn != exclude) send(s.conn, json);
        }
        for (WebSocket sp : spectators) send(sp, json);
    }

    private void sendBoardUpdate() {
        boolean[] aiFlags = new boolean[seats.length];
        for (int i = 0; i < seats.length; i++) aiFlags[i] = seats[i].ai;
        int visibleCurrentPlayer = simultaneousMode ? 0 : currentPlayer;
        for (int i = 0; i < seats.length; i++) {
            send(seats[i].conn, Protocol.boardUpdate(board, visibleCurrentPlayer, characterMode, obstacleMode,
                    simultaneousMode, i + 1, timeLimit, aiFlags, roundNumber, !waitedLastRound[i]));
        }
        if (!spectators.isEmpty()) {
            // 観戦者には所有者不明(0)の視点で送り、誰のトラップも見えない中立視点にする
            String neutral = Protocol.boardUpdate(board, visibleCurrentPlayer, characterMode, obstacleMode,
                    simultaneousMode, 0, timeLimit, aiFlags, roundNumber, true);
            for (WebSocket sp : spectators) send(sp, neutral);
        }
    }

    private void send(WebSocket conn, String json) {
        if (conn != null && conn.isOpen()) conn.send(json);
    }

    private WebSocket getSocket(int playerId) {
        return seats[playerId - 1].conn;
    }

    // --- spectating ---

    public synchronized boolean getPublicRoom() { return publicRoom; }

    public synchronized void setPublicRoom(boolean value) { publicRoom = value; }

    public synchronized boolean isStarted() { return started; }

    public synchronized int getSpectatorCount() { return spectators.size(); }

    public synchronized Seat[] getSeatsSnapshot() { return seats; }

    // 観戦者を追加し、現在の座席状況と盤面(中立視点)を即座に送って途中入室でも追いつけるようにする
    public synchronized void addSpectator(WebSocket conn) {
        spectators.add(conn);
        send(conn, Protocol.spectateJoined(seats, characterMode, obstacleMode, seats.length, timeLimit));
        boolean[] aiFlags = new boolean[seats.length];
        for (int i = 0; i < seats.length; i++) aiFlags[i] = seats[i].ai;
        int visibleCurrentPlayer = simultaneousMode ? 0 : currentPlayer;
        send(conn, Protocol.boardUpdate(board, visibleCurrentPlayer, characterMode, obstacleMode,
                simultaneousMode, 0, timeLimit, aiFlags, roundNumber, true));
    }

    public synchronized void removeSpectator(WebSocket conn) {
        spectators.remove(conn);
    }
}
