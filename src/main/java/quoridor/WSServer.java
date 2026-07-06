package quoridor;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.*;

public class WSServer extends WebSocketServer {

    private final Map<String, PendingRoom> waitingRooms = new HashMap<>();
    private final Map<String, GameRoom> roomsByCode = new HashMap<>();
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
    public synchronized void onMessage(WebSocket conn, String message) {
        GameRoom room = rooms.get(conn);
        if (room != null) {
            room.onMessage(conn, message);
            return;
        }

        try {
            Map<String, Object> data = Protocol.parseJson(message);
            String type = (String) data.get("type");

            if ("CREATE_ROOM".equals(type)) {
                handleCreateRoom(conn, data);
            } else if ("JOIN".equals(type)) {
                handleJoin(conn, data);
            } else if ("SET_CHARACTER".equals(type)) {
                handleSetCharacter(conn, data);
            } else if ("REMATCH".equals(type)) {
                // 解散済みルームの生き残りが再戦を押した場合
                conn.send(Protocol.roomClosed());
            }
        } catch (Exception e) {
            conn.send(Protocol.error("Invalid message: " + e.getMessage()));
        }
    }

    // ホストが設定・スロット構成付きでルームを作成する
    private void handleCreateRoom(WebSocket conn, Map<String, Object> data) {
        String code = normalizeCode((String) data.get("room"));
        if (code == null) {
            conn.send(Protocol.error("Room code is required"));
            return;
        }
        if (waitingRooms.containsKey(code) || roomsByCode.containsKey(code)) {
            conn.send(Protocol.roomCodeTaken());
            return;
        }

        boolean characterMode = parseBool(data.get("characterMode"));
        boolean obstacleMode = parseBool(data.get("obstacleMode"));
        int timeLimit = parseTimeLimit(data.get("timeLimit"));
        int playerCount = parsePlayerCount(data.get("players"));
        int[] slotAi = parseSlotAi(data.get("slotAi"), playerCount);
        CharacterType hostCharacter = parseCharacter((String) data.get("character"), characterMode);

        PendingRoom pending = new PendingRoom(code, characterMode, obstacleMode, timeLimit, playerCount, slotAi);
        pending.humans.add(conn);
        pending.characters.add(hostCharacter);

        System.out.println("Room created: " + code + " (players=" + playerCount
                + ", humans=" + pending.humansNeeded + ", timeLimit=" + timeLimit + ")");

        if (!tryStart(pending)) {
            waitingRooms.put(code, pending);
            conn.send(Protocol.waiting(code, 1, pending.humansNeeded));
        }
    }

    // 合言葉でルームに参加する
    private void handleJoin(WebSocket conn, Map<String, Object> data) {
        String code = normalizeCode((String) data.get("room"));
        if (code == null) {
            conn.send(Protocol.error("Room code is required"));
            return;
        }

        PendingRoom pending = waitingRooms.get(code);
        if (pending == null) {
            // 対局中(=満員)のルームか、存在しないルームか
            if (roomsByCode.containsKey(code)) {
                conn.send(Protocol.roomFull());
            } else {
                conn.send(Protocol.roomNotFound());
            }
            return;
        }
        if (pending.isFull()) {
            conn.send(Protocol.roomFull());
            return;
        }

        pending.humans.add(conn);
        pending.characters.add(CharacterType.NONE);
        conn.send(Protocol.joined(pending.characterMode, pending.obstacleMode,
                pending.playerCount, pending.timeLimit));
        System.out.println("Player joined room: " + code
                + " (" + pending.humans.size() + "/" + pending.humansNeeded + ")");

        if (!tryStart(pending)) {
            for (WebSocket s : pending.humans) {
                send(s, Protocol.waiting(code, pending.humans.size(), pending.humansNeeded));
            }
        }
    }

    // キャラクターモードのルームで、参加者が自分のキャラクターを選ぶ
    private void handleSetCharacter(WebSocket conn, Map<String, Object> data) {
        for (PendingRoom pending : waitingRooms.values()) {
            int idx = pending.humans.indexOf(conn);
            if (idx < 0) continue;

            CharacterType character = parseCharacter((String) data.get("character"), pending.characterMode);
            pending.characters.set(idx, character);
            tryStart(pending);
            return;
        }
    }

    // 人数が揃い(キャラクターモードなら全員選択済みで)開始できるなら開始する
    private boolean tryStart(PendingRoom pending) {
        if (!pending.isFull()) return false;
        if (pending.characterMode) {
            for (CharacterType c : pending.characters) {
                if (c == CharacterType.NONE) return false;
            }
        }

        waitingRooms.remove(pending.code);

        List<GameRoom.Seat> seats = new ArrayList<>();
        int humanIdx = 0;
        for (int i = 0; i < pending.playerCount; i++) {
            if (pending.slotAi[i] > 0) {
                seats.add(GameRoom.Seat.aiSeat(pending.slotAi[i]));
            } else {
                seats.add(GameRoom.Seat.human(pending.humans.get(humanIdx),
                        pending.characters.get(humanIdx)));
                humanIdx++;
            }
        }

        GameRoom gameRoom = new GameRoom(seats, pending.characterMode, pending.obstacleMode, pending.timeLimit);
        for (WebSocket s : pending.humans) {
            rooms.put(s, gameRoom);
            playerRoomCodes.put(s, pending.code);
        }
        roomsByCode.put(pending.code, gameRoom);
        gameRoom.startGame();
        System.out.println("Room " + pending.code + ": game started ("
                + pending.playerCount + " players, " + pending.humans.size() + " humans)");
        return true;
    }

    @Override
    public synchronized void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Disconnected: " + conn.getRemoteSocketAddress());

        // 待機中ルームから離脱した場合
        for (Iterator<Map.Entry<String, PendingRoom>> it = waitingRooms.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PendingRoom> entry = it.next();
            PendingRoom pending = entry.getValue();
            int idx = pending.humans.indexOf(conn);
            if (idx < 0) continue;

            pending.humans.remove(idx);
            pending.characters.remove(idx);
            if (pending.humans.isEmpty()) {
                it.remove();
            } else {
                for (WebSocket s : pending.humans) {
                    send(s, Protocol.waiting(entry.getKey(), pending.humans.size(), pending.humansNeeded));
                }
            }
            break;
        }

        // 対局中/終了後ルームから離脱した場合
        String roomCode = playerRoomCodes.remove(conn);
        GameRoom room = rooms.remove(conn);
        if (room != null) {
            room.onPlayerDisconnected(conn);
            if (room.isDissolved()) {
                if (roomCode != null) roomsByCode.remove(roomCode);
                for (WebSocket other : room.getHumanSockets()) {
                    rooms.remove(other);
                    playerRoomCodes.remove(other);
                }
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

    private void send(WebSocket conn, String json) {
        if (conn != null && conn.isOpen()) conn.send(json);
    }

    private String normalizeCode(String raw) {
        if (raw == null) return null;
        String code = raw.trim().toUpperCase();
        return code.isEmpty() ? null : code;
    }

    private boolean parseBool(Object raw) {
        return raw instanceof Boolean && (Boolean) raw;
    }

    private CharacterType parseCharacter(String raw, boolean characterMode) {
        if (!characterMode) return CharacterType.NONE;
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Character is required");
        }
        CharacterType character = CharacterType.valueOf(raw.trim().toUpperCase());
        if (character == CharacterType.NONE) {
            throw new IllegalArgumentException("Character is required");
        }
        return character;
    }

    private int parsePlayerCount(Object raw) {
        if (raw == null) return 2;
        int n = ((Number) raw).intValue();
        return n == 4 ? 4 : 2;
    }

    private int parseTimeLimit(Object raw) {
        if (!(raw instanceof Number)) return 0;
        int n = ((Number) raw).intValue();
        return Math.max(0, Math.min(600, n));
    }

    // 各席の構成: 0=人間, 1〜3=AI難易度。先頭(ホスト)は常に人間
    private int[] parseSlotAi(Object raw, int playerCount) {
        int[] slots = new int[playerCount];
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            for (int i = 0; i < playerCount && i < list.size(); i++) {
                Object v = list.get(i);
                if (v instanceof Number) {
                    slots[i] = Math.max(0, Math.min(3, ((Number) v).intValue()));
                }
            }
        }
        slots[0] = 0;
        return slots;
    }

    private static class PendingRoom {
        final String code;
        final boolean characterMode;
        final boolean obstacleMode;
        final int timeLimit;
        final int playerCount;
        final int[] slotAi;
        final int humansNeeded;
        final List<WebSocket> humans = new ArrayList<>();
        final List<CharacterType> characters = new ArrayList<>();

        PendingRoom(String code, boolean characterMode, boolean obstacleMode,
                    int timeLimit, int playerCount, int[] slotAi) {
            this.code = code;
            this.characterMode = characterMode;
            this.obstacleMode = obstacleMode;
            this.timeLimit = timeLimit;
            this.playerCount = playerCount;
            this.slotAi = slotAi;
            int humans = 0;
            for (int slot : slotAi) {
                if (slot == 0) humans++;
            }
            this.humansNeeded = humans;
        }

        boolean isFull() {
            return humans.size() >= humansNeeded;
        }
    }
}
