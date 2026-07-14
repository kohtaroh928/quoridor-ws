package quoridor;

import java.util.ArrayList;
import java.util.List;

/** Resolves one simultaneous-mode round (2 or 4 players) without introducing turn priority. */
public final class SimultaneousRound {
    private SimultaneousRound() {}

    public enum Kind { MOVE, WALL, WAIT }

    public static final class Action {
        public final Kind kind;
        public final int x;
        public final int y;
        public final Wall wall;

        private Action(Kind kind, int x, int y, Wall wall) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.wall = wall;
        }

        public static Action move(int x, int y) { return new Action(Kind.MOVE, x, y, null); }
        public static Action wall(Wall wall) { return new Action(Kind.WALL, 0, 0, wall); }
        public static Action waitAction() { return new Action(Kind.WAIT, 0, 0, null); }

        public String label() {
            if (kind == Kind.MOVE) return "(" + x + "," + y + ")へ移動";
            if (kind == Kind.WALL) return "壁(" + wall.getX() + "," + wall.getY() + ","
                    + (wall.getDirection() == Wall.Direction.HORIZONTAL ? "水平" : "垂直") + ")を設置";
            return "待機";
        }
    }

    public static final class Outcome {
        public final boolean[] success;
        public final String[] reason;
        public int winner = -1; // -1: ongoing, 0: draw, 1..N: winner

        public Outcome(int playerCount) {
            success = new boolean[playerCount];
            reason = new String[playerCount];
            java.util.Arrays.fill(reason, "");
        }
    }

    private static boolean isSupportedPlayerCount(int n) {
        return n == 2 || n == 4;
    }

    /** Input-time validation. Opponent-dependent conflicts deliberately remain selectable. */
    public static boolean isValidInput(Board board, int playerId, Action action, boolean canWait) {
        if (!isSupportedPlayerCount(board.getPlayerCount()) || action == null) return false;
        switch (action.kind) {
            case WAIT:
                return canWait;
            case WALL:
                return action.wall != null && GameLogic.isValidWallPlacement(board, playerId, action.wall);
            case MOVE:
                return isSelectableMove(board, playerId, action.x, action.y);
            default:
                return false;
        }
    }

    /** actions[i] is player (i+1)'s chosen action; actions.length must equal board.getPlayerCount(). */
    public static Outcome resolve(Board board, Action[] actions) {
        int n = board.getPlayerCount();
        if (!isSupportedPlayerCount(n) || actions.length != n) {
            throw new IllegalArgumentException("Simultaneous mode requires 2 or 4 players");
        }
        Outcome out = new Outcome(n);

        resolveWalls(board, actions, out);
        resolveMoves(board, actions, out);

        int winner = -1;
        boolean multipleGoals = false;
        for (int playerId = 1; playerId <= n; playerId++) {
            if (GameLogic.checkWin(board, playerId)) {
                if (winner == -1) winner = playerId;
                else multipleGoals = true;
            }
        }
        if (multipleGoals) out.winner = 0;
        else if (winner != -1) out.winner = winner;
        return out;
    }

    private static void resolveWalls(Board board, Action[] actions, Outcome out) {
        int n = actions.length;
        List<Integer> wallIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) if (actions[i].kind == Kind.WALL) wallIdx.add(i);
        if (wallIdx.isEmpty()) return;

        boolean[] individuallyValid = new boolean[n];
        for (int i : wallIdx) {
            individuallyValid[i] = GameLogic.isValidWallPlacement(board, i + 1, actions[i].wall);
            if (!individuallyValid[i]) fail(out, i, "壁を合法に設置できません");
        }

        // 新しく置こうとした壁同士が物理的に競合していないか総当たりでチェックする
        boolean[] conflicted = new boolean[n];
        for (int a = 0; a < wallIdx.size(); a++) {
            int i = wallIdx.get(a);
            if (!individuallyValid[i]) continue;
            for (int b = a + 1; b < wallIdx.size(); b++) {
                int j = wallIdx.get(b);
                if (!individuallyValid[j]) continue;
                if (GameLogic.wallsConflict(actions[i].wall, actions[j].wall)) {
                    conflicted[i] = true;
                    conflicted[j] = true;
                }
            }
        }
        for (int i : wallIdx) {
            if (individuallyValid[i] && conflicted[i]) fail(out, i, "新しい壁同士が競合しました");
        }

        // 残った壁(個別に合法かつ互いに非競合)をまとめて仮設置し、全員の経路が残るか確認する。
        // 経路がなくなる場合はこのグループ全員の設置を失敗させる(一部だけ通す判定はしない)。
        List<Wall> candidateWalls = new ArrayList<>();
        List<Integer> candidateIdx = new ArrayList<>();
        for (int i : wallIdx) {
            if (individuallyValid[i] && !conflicted[i]) {
                candidateWalls.add(actions[i].wall);
                candidateIdx.add(i);
            }
        }
        if (candidateWalls.isEmpty()) return;

        if (!GameLogic.canPlaceWallsTogether(board, candidateWalls)) {
            for (int i : candidateIdx) fail(out, i, "同時に置くとゴール経路がなくなります");
            return;
        }

        for (int k = 0; k < candidateWalls.size(); k++) {
            int i = candidateIdx.get(k);
            placeWall(board, i + 1, candidateWalls.get(k), out, i);
        }
    }

    private static void placeWall(Board board, int playerId, Wall wall, Outcome out, int index) {
        board.addWall(wall);
        board.getPlayer(playerId).useWall();
        out.success[index] = true;
    }

    private static void resolveMoves(Board board, Action[] actions, Outcome out) {
        int n = actions.length;
        int[] sx = new int[n];
        int[] sy = new int[n];
        boolean[] move = new boolean[n];
        boolean[] valid = new boolean[n];

        for (int i = 0; i < n; i++) {
            sx[i] = board.getPlayer(i + 1).getX();
            sy[i] = board.getPlayer(i + 1).getY();
            move[i] = actions[i].kind == Kind.MOVE;
        }

        for (int i = 0; i < n; i++) {
            if (!move[i]) {
                if (actions[i].kind == Kind.WAIT) out.success[i] = true;
                continue;
            }
            valid[i] = isMoveLegalAfterWalls(board, i + 1, actions[i], actions);
            if (!valid[i]) fail(out, i, "壁または相手の行動により移動できません");
        }

        // 2人以上が同じマスへ移動しようとした場合、その全員の移動を失敗させる
        for (int i = 0; i < n; i++) {
            if (!move[i] || !valid[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (!move[j] || !valid[j]) continue;
                if (actions[i].x == actions[j].x && actions[i].y == actions[j].y) {
                    valid[i] = false;
                    valid[j] = false;
                    fail(out, i, "同じマスを選んだため移動が失敗しました");
                    fail(out, j, "同じマスを選んだため移動が失敗しました");
                }
            }
        }

        // 2人1組でお互いの現在地に入れ替わろうとする移動(位置交換)は禁止する
        for (int i = 0; i < n; i++) {
            if (!move[i] || !valid[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (!move[j] || !valid[j]) continue;
                if (actions[i].x == sx[j] && actions[i].y == sy[j]
                        && actions[j].x == sx[i] && actions[j].y == sy[i]) {
                    valid[i] = false;
                    valid[j] = false;
                    fail(out, i, "位置の交換はできません");
                    fail(out, j, "位置の交換はできません");
                }
            }
        }

        // 相手の開始位置へ移動しようとしても、その相手が居座った(退去に失敗した)場合は移動できない。
        // 誰かが失敗すると連鎖的に他の移動も無効になりうるため、変化がなくなるまで繰り返す。
        boolean changed = true;
        for (int pass = 0; pass < n && changed; pass++) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (!move[i] || !valid[i]) continue;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    if (actions[i].x == sx[j] && actions[i].y == sy[j] && !valid[j]) {
                        valid[i] = false;
                        fail(out, i, "相手が開始位置に残るため移動できません");
                        changed = true;
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < n; i++) {
            if (!move[i] || !valid[i]) continue;
            board.getPlayer(i + 1).setPosition(actions[i].x, actions[i].y);
            out.success[i] = true;
            out.reason[i] = "";
        }
    }

    private static boolean isSelectableMove(Board board, int playerId, int toX, int toY) {
        Player me = board.getPlayer(playerId);
        if (!board.isInBounds(toX, toY)) return false;
        int dx = toX - me.getX();
        int dy = toY - me.getY();
        if (Math.abs(dx) + Math.abs(dy) == 1) {
            return !board.isBlocked(me.getX(), me.getY(), toX, toY);
        }
        int n = board.getPlayerCount();
        for (int otherId = 1; otherId <= n; otherId++) {
            if (otherId == playerId) continue;
            if (isJumpOrDiagonal(board, me, board.getPlayer(otherId), toX, toY)) return true;
        }
        return false;
    }

    private static boolean isMoveLegalAfterWalls(Board board, int playerId, Action mine, Action[] actions) {
        Player me = board.getPlayer(playerId);
        int dx = mine.x - me.getX();
        int dy = mine.y - me.getY();
        if (!board.isInBounds(mine.x, mine.y)) return false;
        if (Math.abs(dx) + Math.abs(dy) == 1) {
            return !board.isBlocked(me.getX(), me.getY(), mine.x, mine.y);
        }
        // ジャンプ/斜め移動: 跳び越える相手を特定し、その相手が同時に移動を選んでいないかを見る
        for (int j = 0; j < actions.length; j++) {
            int otherId = j + 1;
            if (otherId == playerId) continue;
            Player other = board.getPlayer(otherId);
            if (isJumpOrDiagonal(board, me, other, mine.x, mine.y)) {
                return actions[j].kind != Kind.MOVE;
            }
        }
        return false;
    }

    private static boolean isJumpOrDiagonal(Board board, Player me, Player other, int toX, int toY) {
        int ox = other.getX() - me.getX();
        int oy = other.getY() - me.getY();
        if (Math.abs(ox) + Math.abs(oy) != 1) return false;
        if (board.isBlocked(me.getX(), me.getY(), other.getX(), other.getY())) return false;

        int behindX = other.getX() + ox;
        int behindY = other.getY() + oy;
        boolean behindOpen = board.isInBounds(behindX, behindY)
                && !board.isBlocked(other.getX(), other.getY(), behindX, behindY);
        if (behindOpen) return toX == behindX && toY == behindY;

        if (ox != 0) {
            if (toX != other.getX() || Math.abs(toY - other.getY()) != 1) return false;
        } else {
            if (toY != other.getY() || Math.abs(toX - other.getX()) != 1) return false;
        }
        return board.isInBounds(toX, toY)
                && !board.isBlocked(other.getX(), other.getY(), toX, toY);
    }

    private static void fail(Outcome out, int index, String reason) {
        out.success[index] = false;
        out.reason[index] = reason;
    }
}
