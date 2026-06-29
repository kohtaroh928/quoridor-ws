package quoridor;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.*;

public class WSServer extends WebSocketServer {

    private final Map<String, WebSocket> waitingRooms = new HashMap<>();
    private final Map<WebSocket, GameRoom> rooms = new HashMap<>();
    private final Map<WebSocket, String> playerRoomCodes = new HashMap<>();

    public WSServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void onMessage(WebSocket conn, String message) {
        GameRoom room = rooms.get(conn);
        if (room != null) {
            room.onMessage(conn, message);
            return;
        }

        try {
            Map<String, Object> data = Protocol.parseJson(message);
            String type = (String) data.get("type");

            if ("JOIN".equals(type)) {
                String code = (String) data.get("room");
                if (code == null || code.trim().isEmpty()) {
                    conn.send(Protocol.error("Room code is required"));
                    return;
                }
                code = code.trim().toUpperCase();
                System.out.println("Player joining room: " + code);

                if (waitingRooms.containsKey(code)) {
                    WebSocket player1 = waitingRooms.remove(code);
                    if (player1.isOpen()) {
                        GameRoom gameRoom = new GameRoom(player1, conn);
                        rooms.put(player1, gameRoom);
                        rooms.put(conn, gameRoom);
                        playerRoomCodes.put(player1, code);
                        playerRoomCodes.put(conn, code);
                        gameRoom.startGame();
                        System.out.println("Room " + code + ": game started!");
                    } else {
                        waitingRooms.put(code, conn);
                        System.out.println("Previous player disconnected, waiting again for room: " + code);
                    }
                } else {
                    waitingRooms.put(code, conn);
                    conn.send("{\"type\":\"WAITING\",\"room\":\"" + code + "\"}");
                    System.out.println("Waiting for opponent in room: " + code);
                }
            }
        } catch (Exception e) {
            conn.send(Protocol.error("Invalid message: " + e.getMessage()));
        }
    }

    @Override
    public synchronized void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Disconnected: " + conn.getRemoteSocketAddress());

        String roomCode = playerRoomCodes.remove(conn);
        waitingRooms.values().remove(conn);

        GameRoom room = rooms.remove(conn);
        if (room != null) {
            room.onPlayerDisconnected(conn);
            WebSocket other = room.getOtherPlayer(conn);
            if (other != null) {
                rooms.remove(other);
                playerRoomCodes.remove(other);
            }
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("Quoridor WebSocket server started on port " + getPort());
    }

    public static void main(String[] args) {
        String envPort = System.getenv("PORT");
        int port = envPort != null ? Integer.parseInt(envPort) : 10000;
        new WSServer(port).start();
    }
}
