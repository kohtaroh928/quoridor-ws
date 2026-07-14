package quoridor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Protocol {

    // --- outgoing message builders ---

    public static String gameStart(int playerNumber) {
        return "{\"type\":\"GAME_START\",\"player\":" + playerNumber + "}";
    }

    public static String boardUpdate(Board board, int currentPlayer) {
        return boardUpdate(board, currentPlayer, false, false, false, 0, 0, null, 0, true);
    }

    public static String boardUpdate(Board board, int currentPlayer, boolean characterMode, boolean obstacleMode,
                                     boolean simultaneousMode, int receiverPlayerId, int timeLimit,
                                     boolean[] aiSeats, int roundNumber, boolean canWait) {
        int n = board.getPlayerCount();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"BOARD_UPDATE\",\"players\":[");
        for (int i = 1; i <= n; i++) {
            Player p = board.getPlayer(i);
            if (i > 1) sb.append(',');
            sb.append("{\"id\":").append(p.getId())
              .append(",\"x\":").append(p.getX())
              .append(",\"y\":").append(p.getY()).append('}');
        }
        sb.append("],\"walls\":[");
        List<Wall> walls = board.getWalls();
        for (int i = 0; i < walls.size(); i++) {
            Wall w = walls.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"x\":").append(w.getX())
              .append(",\"y\":").append(w.getY())
              .append(",\"direction\":\"").append(w.getDirection().name()).append("\"}");
        }
        sb.append("],\"miniWalls\":[");
        List<MiniWall> miniWalls = board.getMiniWalls();
        for (int i = 0; i < miniWalls.size(); i++) {
            MiniWall w = miniWalls.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"x\":").append(w.getX())
              .append(",\"y\":").append(w.getY())
              .append(",\"direction\":\"").append(w.getDirection().name()).append("\"}");
        }
        sb.append("],\"brokenWalls\":[");
        List<Wall> brokenWalls = board.getBrokenWalls();
        for (int i = 0; i < brokenWalls.size(); i++) {
            Wall w = brokenWalls.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"x\":").append(w.getX())
              .append(",\"y\":").append(w.getY())
              .append(",\"direction\":\"").append(w.getDirection().name()).append("\"}");
        }
        sb.append("],\"ownTraps\":[");
        int trapCount = 0;
        for (Trap trap : board.getTraps()) {
            if (!trap.isActive() || trap.getOwnerPlayerId() != receiverPlayerId) continue;
            if (trapCount++ > 0) sb.append(',');
            sb.append("{\"x\":").append(trap.getX())
              .append(",\"y\":").append(trap.getY()).append('}');
        }
        sb.append("],\"currentPlayer\":").append(currentPlayer);
        sb.append(",\"wallsRemaining\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append(board.getPlayer(i).getWallsRemaining());
        }
        sb.append(']');
        sb.append(",\"characterMode\":").append(characterMode);
        sb.append(",\"obstacleMode\":").append(obstacleMode);
        sb.append(",\"simultaneousMode\":").append(simultaneousMode);
        sb.append(",\"roundNumber\":").append(roundNumber);
        sb.append(",\"canWait\":").append(canWait);
        sb.append(",\"characters\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append('"').append(board.getPlayer(i).getCharacterType().name()).append('"');
        }
        sb.append(']');
        sb.append(",\"skillRemaining\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append(board.getPlayer(i).getSkillRemaining());
        }
        sb.append(']');
        sb.append(",\"miniWallsRemaining\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append(board.getPlayer(i).getMiniWallsRemaining());
        }
        sb.append(']');
        sb.append(",\"trapRemaining\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append(board.getPlayer(i).getTrapRemaining());
        }
        sb.append(']');
        sb.append(",\"cannotMove\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append(board.getPlayer(i).cannotMoveNextTurn());
        }
        sb.append(']');
        sb.append(",\"timeLimit\":").append(timeLimit);
        sb.append(",\"ai\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(aiSeats != null && i < aiSeats.length && aiSeats[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    // ロビー(準備待ち)中に、全員のプロフィール・準備状況・ルーム設定を通知する。
    // youは受信者自身の座席番号(1始まり)
    public static String lobbyUpdate(GameRoom.Seat[] seats, boolean[] ready, boolean characterMode,
                                      boolean obstacleMode, boolean simultaneousMode,
                                      int timeLimit, int players, int you, boolean publicRoom) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"LOBBY_UPDATE\",\"you\":").append(you);
        sb.append(",\"seats\":[");
        for (int i = 0; i < seats.length; i++) {
            GameRoom.Seat s = seats[i];
            if (i > 0) sb.append(',');
            boolean isReady = s.ai || (ready != null && i < ready.length && ready[i]);
            boolean joined = s.ai || s.conn != null;
            sb.append("{\"name\":\"").append(escapeJson(s.name == null ? "" : s.name))
              .append("\",\"avatarId\":").append(s.avatarId)
              .append(",\"ai\":").append(s.ai)
              .append(",\"ready\":").append(isReady)
              .append(",\"joined\":").append(joined)
              .append(",\"difficulty\":").append(s.aiDifficulty)
              .append(",\"character\":\"").append(s.character.name()).append('"')
              .append('}');
        }
        sb.append("],\"characterMode\":").append(characterMode);
        sb.append(",\"obstacleMode\":").append(obstacleMode);
        sb.append(",\"simultaneousMode\":").append(simultaneousMode);
        sb.append(",\"timeLimit\":").append(timeLimit);
        sb.append(",\"players\":").append(players);
        sb.append(",\"publicRoom\":").append(publicRoom);
        sb.append('}');
        return sb.toString();
    }

    // 観戦者がルームに参加した直後に、現在の座席状況(名前・アバター・キャラクター)を伝える
    public static String spectateJoined(GameRoom.Seat[] seats, boolean characterMode, boolean obstacleMode,
                                         int players, int timeLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"SPECTATE_JOINED\",\"seats\":[");
        for (int i = 0; i < seats.length; i++) {
            GameRoom.Seat s = seats[i];
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(escapeJson(s.name == null ? "" : s.name))
              .append("\",\"avatarId\":").append(s.avatarId)
              .append(",\"ai\":").append(s.ai)
              .append(",\"ready\":true")
              .append(",\"joined\":true")
              .append(",\"difficulty\":").append(s.aiDifficulty)
              .append(",\"character\":\"").append(s.character.name()).append('"')
              .append('}');
        }
        sb.append("],\"characterMode\":").append(characterMode);
        sb.append(",\"obstacleMode\":").append(obstacleMode);
        sb.append(",\"timeLimit\":").append(timeLimit);
        sb.append(",\"players\":").append(players);
        sb.append('}');
        return sb.toString();
    }

    // 観戦可能な(公開設定かつ対局中の)ルーム一覧
    public static String roomList(List<RoomSummary> rooms) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"ROOM_LIST\",\"rooms\":[");
        for (int i = 0; i < rooms.size(); i++) {
            RoomSummary r = rooms.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"code\":\"").append(r.code).append('"')
              .append(",\"players\":").append(r.players)
              .append(",\"characterMode\":").append(r.characterMode)
              .append(",\"obstacleMode\":").append(r.obstacleMode)
              .append(",\"spectators\":").append(r.spectators)
              .append(",\"names\":[");
            for (int j = 0; j < r.names.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(escapeJson(r.names.get(j))).append('"');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public static class RoomSummary {
        public final String code;
        public final int players;
        public final boolean characterMode;
        public final boolean obstacleMode;
        public final int spectators;
        public final List<String> names;

        public RoomSummary(String code, int players, boolean characterMode, boolean obstacleMode,
                            int spectators, List<String> names) {
            this.code = code;
            this.players = players;
            this.characterMode = characterMode;
            this.obstacleMode = obstacleMode;
            this.spectators = spectators;
            this.names = names;
        }
    }

    public static String gameEnd(int winner) {
        return gameEnd(winner, false);
    }

    public static String gameEnd(int winner, boolean draw) {
        return "{\"type\":\"GAME_END\",\"winner\":" + winner + ",\"draw\":" + draw + "}";
    }

    public static String error(String message) {
        return "{\"type\":\"ERROR\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    public static String rematchRequested() {
        return "{\"type\":\"REMATCH_REQUESTED\"}";
    }

    public static String returnToLobbyRequested() {
        return "{\"type\":\"RETURN_TO_LOBBY_REQUESTED\"}";
    }

    public static String roomClosed() {
        return "{\"type\":\"ROOM_CLOSED\"}";
    }

    // ルーム参加成功時に、参加者へルーム設定を伝える
    public static String joined(boolean characterMode, boolean obstacleMode, boolean simultaneousMode,
                                int players, int timeLimit) {
        return "{\"type\":\"JOINED\",\"characterMode\":" + characterMode
            + ",\"obstacleMode\":" + obstacleMode
            + ",\"simultaneousMode\":" + simultaneousMode
            + ",\"players\":" + players
            + ",\"timeLimit\":" + timeLimit + "}";
    }

    public static String actionAccepted(int roundNumber) {
        return "{\"type\":\"ACTION_ACCEPTED\",\"roundNumber\":" + roundNumber + "}";
    }

    public static String roundResult(int roundNumber, SimultaneousRound.Action[] actions,
                                     SimultaneousRound.Outcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"ROUND_RESULT\",\"roundNumber\":").append(roundNumber);
        sb.append(",\"actions\":[");
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(actions[i].label())).append('"');
        }
        sb.append("],\"success\":[");
        for (int i = 0; i < outcome.success.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(outcome.success[i]);
        }
        sb.append("],\"reasons\":[");
        for (int i = 0; i < outcome.reason.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(outcome.reason[i])).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static String roomFull() {
        return "{\"type\":\"ROOM_FULL\"}";
    }

    public static String roomNotFound() {
        return "{\"type\":\"ROOM_NOT_FOUND\"}";
    }

    // 合言葉が既存ルームと衝突した場合(クライアントは再生成して再送する)
    public static String roomCodeTaken() {
        return "{\"type\":\"ROOM_CODE_TAKEN\"}";
    }

    // ゲームを中断せずに画面へ流す通知(タイムアウトスキップ・AI交代など)
    public static String notice(String message) {
        return "{\"type\":\"NOTICE\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    // --- incoming message parser ---

    public static ClientMessage parseClientMessage(String json) {
        Map<String, Object> data = parseJson(json);
        String type = (String) data.get("type");

        if ("MOVE".equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> to = (Map<String, Object>) data.get("to");
            int x = ((Number) to.get("x")).intValue();
            int y = ((Number) to.get("y")).intValue();
            return ClientMessage.move(x, y);
        }
        if ("PLACE_WALL".equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pos = (Map<String, Object>) data.get("position");
            int x = ((Number) pos.get("x")).intValue();
            int y = ((Number) pos.get("y")).intValue();
            String dir = (String) data.get("direction");
            return ClientMessage.placeWall(x, y, dir);
        }
        if ("WAIT".equals(type)) {
            return ClientMessage.waitAction();
        }
        if ("USE_SKILL".equals(type)) {
            String skill = (String) data.get("skill");
            if ("RUNNER_MOVE".equals(skill) || "ACROBAT_MOVE".equals(skill)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> to = (Map<String, Object>) data.get("to");
                int x = ((Number) to.get("x")).intValue();
                int y = ((Number) to.get("y")).intValue();
                return ClientMessage.skillMove(skill, x, y);
            }
            if ("BREAK_WALL".equals(skill)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> target = (Map<String, Object>) data.get("targetWall");
                int x = ((Number) target.get("x")).intValue();
                int y = ((Number) target.get("y")).intValue();
                String dir = (String) target.get("direction");
                return ClientMessage.breakWall(x, y, dir);
            }
            if ("PLACE_MINI_WALLS".equals(skill)) {
                @SuppressWarnings("unchecked")
                List<Object> rawWalls = (List<Object>) data.get("miniWalls");
                List<MiniWall> miniWalls = new ArrayList<>();
                if (rawWalls != null) {
                    for (Object item : rawWalls) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rawWall = (Map<String, Object>) item;
                        int x = ((Number) rawWall.get("x")).intValue();
                        int y = ((Number) rawWall.get("y")).intValue();
                        String dir = (String) rawWall.get("direction");
                        miniWalls.add(new MiniWall(x, y, MiniWall.Direction.valueOf(dir)));
                    }
                }
                return ClientMessage.placeMiniWalls(miniWalls);
            }
        }
        if ("PLACE_TRAP".equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pos = (Map<String, Object>) data.get("position");
            int x = ((Number) pos.get("x")).intValue();
            int y = ((Number) pos.get("y")).intValue();
            return ClientMessage.placeTrap(x, y);
        }
        throw new IllegalArgumentException("Unknown message type: " + type);
    }

    public static class ClientMessage {
        public final String type;
        public final String skill;
        public final int toX, toY;
        public final int wallX, wallY;
        public final Wall.Direction wallDirection;
        public final List<MiniWall> miniWalls;

        private ClientMessage(String type, String skill, int toX, int toY, int wallX, int wallY,
                              Wall.Direction wallDir, List<MiniWall> miniWalls) {
            this.type = type;
            this.skill = skill;
            this.toX = toX;
            this.toY = toY;
            this.wallX = wallX;
            this.wallY = wallY;
            this.wallDirection = wallDir;
            this.miniWalls = miniWalls;
        }

        static ClientMessage move(int x, int y) {
            return new ClientMessage("MOVE", null, x, y, 0, 0, null, null);
        }

        static ClientMessage placeWall(int x, int y, String dir) {
            Wall.Direction d = Wall.Direction.valueOf(dir);
            return new ClientMessage("PLACE_WALL", null, 0, 0, x, y, d, null);
        }

        static ClientMessage waitAction() {
            return new ClientMessage("WAIT", null, 0, 0, 0, 0, null, null);
        }

        static ClientMessage skillMove(String skill, int x, int y) {
            return new ClientMessage("USE_SKILL", skill, x, y, 0, 0, null, null);
        }

        static ClientMessage breakWall(int x, int y, String dir) {
            Wall.Direction d = Wall.Direction.valueOf(dir);
            return new ClientMessage("USE_SKILL", "BREAK_WALL", 0, 0, x, y, d, null);
        }

        static ClientMessage placeMiniWalls(List<MiniWall> miniWalls) {
            return new ClientMessage("USE_SKILL", "PLACE_MINI_WALLS", 0, 0, 0, 0, null, miniWalls);
        }

        static ClientMessage placeTrap(int x, int y) {
            return new ClientMessage("PLACE_TRAP", null, x, y, 0, 0, null, null);
        }
    }

    // --- thread-safe JSON parser ---

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJson(String json) {
        return (Map<String, Object>) new JsonParser(json.trim()).parseValue();
    }

    private static class JsonParser {
        private final String src;
        private int idx;

        JsonParser(String src) {
            this.src = src;
            this.idx = 0;
        }

        Object parseValue() {
            skipWs();
            char c = src.charAt(idx);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': case 'f': return parseBoolean();
                case 'n': idx += 4; return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                    throw new RuntimeException("Unexpected char '" + c + "' at " + idx);
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            idx++;
            skipWs();
            if (src.charAt(idx) != '}') {
                parseEntry(map);
                skipWs();
                while (src.charAt(idx) == ',') {
                    idx++;
                    parseEntry(map);
                    skipWs();
                }
            }
            idx++;
            return map;
        }

        private void parseEntry(Map<String, Object> map) {
            skipWs();
            String key = parseString();
            skipWs();
            idx++;
            Object value = parseValue();
            map.put(key, value);
        }

        private java.util.List<Object> parseArray() {
            java.util.List<Object> list = new java.util.ArrayList<>();
            idx++;
            skipWs();
            if (src.charAt(idx) != ']') {
                list.add(parseValue());
                skipWs();
                while (src.charAt(idx) == ',') {
                    idx++;
                    list.add(parseValue());
                    skipWs();
                }
            }
            idx++;
            return list;
        }

        private String parseString() {
            idx++;
            StringBuilder sb = new StringBuilder();
            while (src.charAt(idx) != '"') {
                if (src.charAt(idx) == '\\') {
                    idx++;
                    char esc = src.charAt(idx);
                    switch (esc) {
                        case '"': case '\\': case '/': sb.append(esc); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            // 4桁16進のUnicodeエスケープ。日本語の名前など非ASCII文字が
                            // エスケープされて届く場合に対応する
                            sb.append((char) Integer.parseInt(src.substring(idx + 1, idx + 5), 16));
                            idx += 4;
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(src.charAt(idx));
                }
                idx++;
            }
            idx++;
            return sb.toString();
        }

        private Number parseNumber() {
            int start = idx;
            if (src.charAt(idx) == '-') idx++;
            while (idx < src.length() && src.charAt(idx) >= '0' && src.charAt(idx) <= '9') idx++;
            return Integer.parseInt(src.substring(start, idx));
        }

        private boolean parseBoolean() {
            if (src.startsWith("true", idx)) { idx += 4; return true; }
            if (src.startsWith("false", idx)) { idx += 5; return false; }
            throw new RuntimeException("Invalid boolean at " + idx);
        }

        private void skipWs() {
            while (idx < src.length() && src.charAt(idx) <= ' ') idx++;
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t");
    }
}
