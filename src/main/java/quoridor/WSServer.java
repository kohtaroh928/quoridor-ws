package quoridor;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.*;

public class WSServer extends WebSocketServer {

    private final Queue<WebSocket> waiting = new LinkedList<>();
    private final Map<WebSocket, GameRoom> rooms = new HashMap<>();

    public WSServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public synchronized void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Player connected: " + conn.getRemoteSocketAddress());
        if (waiting.isEmpty()) {
            waiting.add(conn);
            System.out.println("Waiting for opponent...");
        } else {
            WebSocket player1 = waiting.poll();
            GameRoom room = new GameRoom(player1, conn);
            rooms.put(player1, room);
            rooms.put(conn, room);
            room.startGame();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        GameRoom room;
        synchronized (this) { room = rooms.get(conn); }
        if (room != null) room.onMessage(conn, message);
    }

    @Override
    public synchronized void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Player disconnected: " + conn.getRemoteSocketAddress());
        waiting.remove(conn);
        GameRoom room = rooms.remove(conn);
        if (room != null) {
            room.onPlayerDisconnected(conn);
            WebSocket other = room.getOtherPlayer(conn);
            if (other != null) rooms.remove(other);
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
