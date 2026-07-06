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
    private final boolean characterMode;
    private final boolean obstacleMode;
    private final int timeLimit; // 1ターンの秒数(0=無制限)
    private int currentPlayer = 1;
    private boolean gameOver = false;
    private boolean dissolved = false;
    private boolean started = false; // trueになるまではロビー(準備待ち)状態
    private final boolean[] readyFlags;
    private final boolean[] rematchRequested;

    // ターンごとに増える通し番号。古いタイマー/AIタスクの誤発火を防ぐ
    private int turnSerial = 0;
    private int scheduledSerial = -1;
    private ScheduledFuture<?> pendingTask;

    public GameRoom(List<Seat> seatList, boolean characterMode, boolean obstacleMode, int timeLimit) {
        this.seats = seatList.toArray(new Seat[0]);
        this.board = new Board(this.seats.length);
        this.characterMode = characterMode;
        this.obstacleMode = obstacleMode;
        this.timeLimit = timeLimit;
        this.rematchRequested = new boolean[this.seats.length];
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
        scheduleTurn();
        System.out.println("Game started! (" + seats.length + " players, "
                + humanCount() + " humans, timeLimit=" + timeLimit + ")");
    }

    private void handleReady(int playerId) {
        if (started || dissolved) return;
        Seat seat = seats[playerId - 1];
        if (seat.ai) return;
        readyFlags[playerId - 1] = true;
        System.out.println("Player " + playerId + " is ready.");

        boolean allReady = true;
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].ai) continue; // AI席は自動で準備完了扱い
            if (!readyFlags[i]) { allReady = false; break; }
        }

        if (allReady) {
            startGame();
        } else {
            broadcastLobby();
        }
    }

    private void broadcastLobby() {
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].conn != null) {
                send(seats[i].conn, Protocol.lobbyUpdate(seats, readyFlags, characterMode, obstacleMode,
                        timeLimit, seats.length, i + 1));
            }
        }
    }

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

            if (!started) return; // ロビー中は準備完了メッセージ以外を無視する

            if ("REMATCH".equals(type)) {
                handleRematch(playerId);
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

    // 現在の手番に応じて、AIの一手かターンタイムアウトを予約する。
    // 同じターンに対しては一度しか予約しない(トラップ設置は手番が続くため再予約されない)
    private void scheduleTurn() {
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
        turnSerial++;
        gameOver = false;
        for (int i = 0; i < rematchRequested.length; i++) rematchRequested[i] = false;
        for (int i = 0; i < seats.length; i++) {
            send(seats[i].conn, Protocol.gameStart(i + 1));
        }
        sendBoardUpdate();
        scheduleTurn();
        System.out.println("Rematch started!");
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
    }

    private void broadcastExcept(WebSocket exclude, String json) {
        for (Seat s : seats) {
            if (s.conn != exclude) send(s.conn, json);
        }
    }

    private void sendBoardUpdate() {
        boolean[] aiFlags = new boolean[seats.length];
        for (int i = 0; i < seats.length; i++) aiFlags[i] = seats[i].ai;
        for (int i = 0; i < seats.length; i++) {
            send(seats[i].conn, Protocol.boardUpdate(board, currentPlayer, characterMode, obstacleMode,
                    i + 1, timeLimit, aiFlags));
        }
    }

    private void send(WebSocket conn, String json) {
        if (conn != null && conn.isOpen()) conn.send(json);
    }

    private WebSocket getSocket(int playerId) {
        return seats[playerId - 1].conn;
    }
}
