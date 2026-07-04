package quoridor;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.*;

public class WSServer extends WebSocketServer {

    private final Map<String, PendingPlayer> waitingRooms = new HashMap<>();
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
                GameMode mode = parseGameMode((String) data.get("mode"));
                CharacterType character = parseCharacter((String) data.get("character"), mode);
                System.out.println("Player joining room: " + code);

                if (waitingRooms.containsKey(code)) {
                    PendingPlayer pending = waitingRooms.remove(code);
                    WebSocket player1 = pending.socket;
                    if (player1.isOpen()) {
                        if (pending.mode != mode) {
                            conn.send(Protocol.error("Room mode does not match"));
                            waitingRooms.put(code, pending);
                            return;
                        }
                        GameRoom gameRoom = new GameRoom(player1, conn, mode, pending.character, character);
                        rooms.put(player1, gameRoom);
                        rooms.put(conn, gameRoom);
                        playerRoomCodes.put(player1, code);
                        playerRoomCodes.put(conn, code);
                        gameRoom.startGame();
                        System.out.println("Room " + code + ": game started!");
                    } else {
                        waitingRooms.put(code, new PendingPlayer(conn, mode, character));
                        System.out.println("Previous player disconnected, waiting again for room: " + code);
                    }
                } else {
                    waitingRooms.put(code, new PendingPlayer(conn, mode, character));
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
        waitingRooms.values().removeIf(p -> p.socket == conn);

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

    private GameMode parseGameMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) return GameMode.NORMAL;
        return GameMode.valueOf(raw.trim().toUpperCase());
    }

    private CharacterType parseCharacter(String raw, GameMode mode) {
        if (mode == GameMode.NORMAL) return CharacterType.NONE;
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Character is required");
        }
        CharacterType character = CharacterType.valueOf(raw.trim().toUpperCase());
        if (character == CharacterType.NONE) {
            throw new IllegalArgumentException("Character is required");
        }
        return character;
    }

    private static class PendingPlayer {
        final WebSocket socket;
        final GameMode mode;
        final CharacterType character;

        PendingPlayer(WebSocket socket, GameMode mode, CharacterType character) {
            this.socket = socket;
            this.mode = mode;
            this.character = character;
        }
    }
}
