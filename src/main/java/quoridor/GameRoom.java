package quoridor;

import org.java_websocket.WebSocket;

import java.util.List;

public class GameRoom {

    private final WebSocket player1;
    private final WebSocket player2;
    private final Board board;
    private final GameMode gameMode;
    private int currentPlayer = 1;
    private boolean gameOver = false;
    private final boolean[] rematchRequested = new boolean[2];

    public GameRoom(WebSocket p1, WebSocket p2) {
        this(p1, p2, GameMode.NORMAL, CharacterType.NONE, CharacterType.NONE);
    }

    public GameRoom(WebSocket p1, WebSocket p2, GameMode mode, CharacterType c1, CharacterType c2) {
        this.player1 = p1;
        this.player2 = p2;
        this.board = new Board();
        this.gameMode = mode == null ? GameMode.NORMAL : mode;
        board.getPlayer(1).setCharacter(this.gameMode == GameMode.CHARACTER ? c1 : CharacterType.NONE);
        board.getPlayer(2).setCharacter(this.gameMode == GameMode.CHARACTER ? c2 : CharacterType.NONE);
    }

    public void startGame() {
        send(player1, Protocol.gameStart(1));
        send(player2, Protocol.gameStart(2));
        sendBoardUpdate();
        System.out.println("Game started!");
    }

    public synchronized void onMessage(WebSocket conn, String message) {
        int playerId = (conn == player1) ? 1 : 2;

        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = Protocol.parseJson(message);
            String type = (String) data.get("type");

            if ("REMATCH".equals(type)) {
                handleRematch(playerId);
                return;
            }

            if (gameOver) return;

            if ("SURRENDER".equals(type)) {
                int winner = (playerId == 1) ? 2 : 1;
                broadcast(Protocol.gameEnd(winner));
                gameOver = true;
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
        if (gameMode != GameMode.CHARACTER) {
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
        if (gameMode != GameMode.CHARACTER) {
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
            gameOver = true;
            System.out.println("Player " + playerId + " wins!");
            return;
        }
        nextTurn();
        sendBoardUpdate();
    }

    public void onPlayerDisconnected(WebSocket conn) {
        WebSocket other = getOtherPlayer(conn);
        if (!gameOver) {
            gameOver = true;
            if (other != null && other.isOpen()) {
                send(other, Protocol.error("Opponent disconnected"));
                int winner = (conn == player1) ? 2 : 1;
                send(other, Protocol.gameEnd(winner));
            }
            return;
        }
        // ゲーム終了後(再戦待ち中を含む)に相手が退出した場合、待機側に通知する
        if (other != null && other.isOpen()) {
            send(other, Protocol.roomClosed());
        }
    }

    private void handleRematch(int playerId) {
        if (!gameOver) return;
        rematchRequested[playerId - 1] = true;
        if (rematchRequested[0] && rematchRequested[1]) {
            startRematch();
        } else {
            send(getSocket(playerId == 1 ? 2 : 1), Protocol.rematchRequested());
        }
    }

    private void startRematch() {
        board.reset();
        currentPlayer = 1;
        gameOver = false;
        rematchRequested[0] = false;
        rematchRequested[1] = false;
        send(player1, Protocol.gameStart(1));
        send(player2, Protocol.gameStart(2));
        sendBoardUpdate();
        System.out.println("Rematch started!");
    }

    public WebSocket getOtherPlayer(WebSocket conn) {
        return (conn == player1) ? player2 : player1;
    }

    public boolean isGameOver() { return gameOver; }

    private void nextTurn() {
        currentPlayer = (currentPlayer == 1) ? 2 : 1;
    }

    private void broadcast(String json) {
        send(player1, json);
        send(player2, json);
    }

    private void sendBoardUpdate() {
        send(player1, Protocol.boardUpdate(board, currentPlayer, gameMode, 1));
        send(player2, Protocol.boardUpdate(board, currentPlayer, gameMode, 2));
    }

    private void send(WebSocket conn, String json) {
        if (conn != null && conn.isOpen()) conn.send(json);
    }

    private WebSocket getSocket(int playerId) {
        return playerId == 1 ? player1 : player2;
    }
}
