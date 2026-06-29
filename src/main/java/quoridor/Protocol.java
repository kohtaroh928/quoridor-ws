package quoridor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Protocol {

    // --- outgoing message builders ---

    public static String gameStart(int playerNumber) {
        return "{\"type\":\"GAME_START\",\"player\":" + playerNumber + "}";
    }

    public static String boardUpdate(Board board, int currentPlayer) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"BOARD_UPDATE\",\"players\":[");
        for (int i = 1; i <= 2; i++) {
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
        sb.append("],\"currentPlayer\":").append(currentPlayer);
        sb.append(",\"wallsRemaining\":{\"1\":")
          .append(board.getPlayer(1).getWallsRemaining())
          .append(",\"2\":")
          .append(board.getPlayer(2).getWallsRemaining())
          .append("}}");
        return sb.toString();
    }

    public static String gameEnd(int winner) {
        return "{\"type\":\"GAME_END\",\"winner\":" + winner + "}";
    }

    public static String error(String message) {
        return "{\"type\":\"ERROR\",\"message\":\"" + escapeJson(message) + "\"}";
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
        throw new IllegalArgumentException("Unknown message type: " + type);
    }

    public static class ClientMessage {
        public final String type;
        public final int toX, toY;
        public final int wallX, wallY;
        public final Wall.Direction wallDirection;

        private ClientMessage(String type, int toX, int toY, int wallX, int wallY, Wall.Direction wallDir) {
            this.type = type;
            this.toX = toX;
            this.toY = toY;
            this.wallX = wallX;
            this.wallY = wallY;
            this.wallDirection = wallDir;
        }

        static ClientMessage move(int x, int y) {
            return new ClientMessage("MOVE", x, y, 0, 0, null);
        }

        static ClientMessage placeWall(int x, int y, String dir) {
            Wall.Direction d = Wall.Direction.valueOf(dir);
            return new ClientMessage("PLACE_WALL", 0, 0, x, y, d);
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
