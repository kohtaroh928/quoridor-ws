package quoridor;

import org.java_websocket.WebSocket;

public class GameRoom {

    private final WebSocket player1;
    private final WebSocket player2;
    private final Board board;
    private int currentPlayer = 1;
    private boolean gameOver = false;

    public GameRoom(WebSocket p1, WebSocket p2) {
        this.player1 = p1;
        this.player2 = p2;
        this.board = new Board();
    }

    public void startGame() {
        send(player1, Protocol.gameStart(1));
        send(player2, Protocol.gameStart(2));
        broadcast(Protocol.boardUpdate(board, currentPlayer));
        System.out.println("Game started!");
    }

    public synchronized void onMessage(WebSocket conn, String message) {
        if (gameOver) return;
        int playerId = (conn == player1) ? 1 : 2;

        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = Protocol.parseJson(message);
            String type = (String) data.get("type");

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
        board.getPlayer(playerId).setPosition(toX, toY);
        System.out.println("Player " + playerId + " moved to (" + toX + "," + toY + ")");

        if (GameLogic.checkWin(board, playerId)) {
            broadcast(Protocol.boardUpdate(board, currentPlayer));
            broadcast(Protocol.gameEnd(playerId));
            gameOver = true;
            System.out.println("Player " + playerId + " wins!");
            return;
        }
        nextTurn();
        broadcast(Protocol.boardUpdate(board, currentPlayer));
    }

    private void handlePlaceWall(int playerId, int x, int y, Wall.Direction dir) {
        Wall wall = new Wall(x, y, dir);
        if (!GameLogic.isValidWallPlacement(board, playerId, wall)) {
            send(getSocket(playerId), Protocol.error("Invalid wall"));
            return;
        }
        board.addWall(wall);
        board.getPlayer(playerId).useWall();
        System.out.println("Player " + playerId + " placed wall " + wall);
        nextTurn();
        broadcast(Protocol.boardUpdate(board, currentPlayer));
    }

    public void onPlayerDisconnected(WebSocket conn) {
        if (gameOver) return;
        gameOver = true;
        WebSocket other = getOtherPlayer(conn);
        if (other != null && other.isOpen()) {
            send(other, Protocol.error("Opponent disconnected"));
            int winner = (conn == player1) ? 2 : 1;
            send(other, Protocol.gameEnd(winner));
        }
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

    private void send(WebSocket conn, String json) {
        if (conn != null && conn.isOpen()) conn.send(json);
    }

    private WebSocket getSocket(int playerId) {
        return playerId == 1 ? player1 : player2;
    }
}
