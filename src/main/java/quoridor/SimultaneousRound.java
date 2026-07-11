package quoridor;

/** Resolves one two-player simultaneous-mode round without introducing turn priority. */
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
        public final boolean[] success = new boolean[2];
        public final String[] reason = {"", ""};
        public int winner = -1; // -1: ongoing, 0: draw, 1/2: winner
    }

    /** Input-time validation. Opponent-dependent conflicts deliberately remain selectable. */
    public static boolean isValidInput(Board board, int playerId, Action action, boolean canWait) {
        if (board.getPlayerCount() != 2 || action == null) return false;
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

    public static Outcome resolve(Board board, Action first, Action second) {
        if (board.getPlayerCount() != 2) throw new IllegalArgumentException("Simultaneous mode requires 2 players");
        Action[] actions = {first, second};
        Outcome out = new Outcome();

        resolveWalls(board, actions, out);
        resolveMoves(board, actions, out);

        boolean p1Goal = GameLogic.checkWin(board, 1);
        boolean p2Goal = GameLogic.checkWin(board, 2);
        if (p1Goal && p2Goal) out.winner = 0;
        else if (p1Goal) out.winner = 1;
        else if (p2Goal) out.winner = 2;
        return out;
    }

    private static void resolveWalls(Board board, Action[] actions, Outcome out) {
        boolean w1 = actions[0].kind == Kind.WALL;
        boolean w2 = actions[1].kind == Kind.WALL;
        if (!w1 && !w2) return;

        boolean valid1 = w1 && GameLogic.isValidWallPlacement(board, 1, actions[0].wall);
        boolean valid2 = w2 && GameLogic.isValidWallPlacement(board, 2, actions[1].wall);

        if (w1 && w2 && valid1 && valid2) {
            if (GameLogic.wallsConflict(actions[0].wall, actions[1].wall)) {
                fail(out, 0, "新しい壁同士が競合しました");
                fail(out, 1, "新しい壁同士が競合しました");
                return;
            }
            if (!GameLogic.canPlaceWallsTogether(board, actions[0].wall, actions[1].wall)) {
                fail(out, 0, "2枚を同時に置くとゴール経路がなくなります");
                fail(out, 1, "2枚を同時に置くとゴール経路がなくなります");
                return;
            }
            placeWall(board, 1, actions[0].wall, out, 0);
            placeWall(board, 2, actions[1].wall, out, 1);
            return;
        }

        if (w1) {
            if (valid1) placeWall(board, 1, actions[0].wall, out, 0);
            else fail(out, 0, "壁を合法に設置できません");
        }
        if (w2) {
            if (valid2) placeWall(board, 2, actions[1].wall, out, 1);
            else fail(out, 1, "壁を合法に設置できません");
        }
    }

    private static void placeWall(Board board, int playerId, Wall wall, Outcome out, int index) {
        board.addWall(wall);
        board.getPlayer(playerId).useWall();
        out.success[index] = true;
    }

    private static void resolveMoves(Board board, Action[] actions, Outcome out) {
        int[] sx = {board.getPlayer(1).getX(), board.getPlayer(2).getX()};
        int[] sy = {board.getPlayer(1).getY(), board.getPlayer(2).getY()};
        boolean[] move = {actions[0].kind == Kind.MOVE, actions[1].kind == Kind.MOVE};
        boolean[] valid = new boolean[2];

        for (int i = 0; i < 2; i++) {
            if (!move[i]) {
                if (actions[i].kind == Kind.WAIT) out.success[i] = true;
                continue;
            }
            valid[i] = isMoveLegalAfterWalls(board, i + 1, actions[i], actions[1 - i]);
            if (!valid[i]) fail(out, i, "壁または相手の行動により移動できません");
        }

        if (move[0] && move[1] && valid[0] && valid[1]) {
            if (actions[0].x == actions[1].x && actions[0].y == actions[1].y) {
                valid[0] = valid[1] = false;
                fail(out, 0, "同じマスを選んだため両者の移動が失敗しました");
                fail(out, 1, "同じマスを選んだため両者の移動が失敗しました");
            } else if (actions[0].x == sx[1] && actions[0].y == sy[1]
                    && actions[1].x == sx[0] && actions[1].y == sy[0]) {
                valid[0] = valid[1] = false;
                fail(out, 0, "位置の交換はできません");
                fail(out, 1, "位置の交換はできません");
            }
        }

        // Moving into the opponent's start succeeds only if that opponent actually vacates it.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 2; i++) {
                int other = 1 - i;
                if (valid[i] && actions[i].x == sx[other] && actions[i].y == sy[other] && !valid[other]) {
                    valid[i] = false;
                    fail(out, i, "相手が開始位置に残るため移動できません");
                }
            }
        }

        for (int i = 0; i < 2; i++) {
            if (!move[i] || !valid[i]) continue;
            board.getPlayer(i + 1).setPosition(actions[i].x, actions[i].y);
            out.success[i] = true;
            out.reason[i] = "";
        }
    }

    private static boolean isSelectableMove(Board board, int playerId, int toX, int toY) {
        Player me = board.getPlayer(playerId);
        Player other = board.getPlayer(playerId == 1 ? 2 : 1);
        if (!board.isInBounds(toX, toY)) return false;
        int dx = toX - me.getX();
        int dy = toY - me.getY();
        if (Math.abs(dx) + Math.abs(dy) == 1) {
            return !board.isBlocked(me.getX(), me.getY(), toX, toY);
        }
        return isJumpOrDiagonal(board, me, other, toX, toY);
    }

    private static boolean isMoveLegalAfterWalls(Board board, int playerId, Action mine, Action otherAction) {
        Player me = board.getPlayer(playerId);
        Player other = board.getPlayer(playerId == 1 ? 2 : 1);
        int dx = mine.x - me.getX();
        int dy = mine.y - me.getY();
        if (!board.isInBounds(mine.x, mine.y)) return false;
        if (Math.abs(dx) + Math.abs(dy) == 1) {
            return !board.isBlocked(me.getX(), me.getY(), mine.x, mine.y);
        }
        return otherAction.kind != Kind.MOVE && isJumpOrDiagonal(board, me, other, mine.x, mine.y);
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
