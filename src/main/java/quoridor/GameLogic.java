package quoridor;

import java.util.ArrayList;
import java.util.List;

public class GameLogic {

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    public static List<int[]> getValidMoves(Board board, int playerId) {
        List<int[]> moves = new ArrayList<>();
        Player player = board.getPlayer(playerId);
        if (player.cannotMoveNextTurn()) return moves;

        Player opponent = board.getOpponent(playerId);
        int px = player.getX(), py = player.getY();
        int ox = opponent.getX(), oy = opponent.getY();

        for (int[] d : DIRS) {
            int nx = px + d[0];
            int ny = py + d[1];

            if (!board.isInBounds(nx, ny)) continue;
            if (board.isBlocked(px, py, nx, ny)) continue;

            if (nx == ox && ny == oy) {
                int jx = nx + d[0];
                int jy = ny + d[1];

                if (board.isInBounds(jx, jy) && !board.isBlocked(nx, ny, jx, jy)) {
                    moves.add(new int[]{jx, jy});
                } else {
                    for (int[] pd : perpendicular(d)) {
                        int dx = nx + pd[0];
                        int dy = ny + pd[1];
                        if (board.isInBounds(dx, dy) && !board.isBlocked(nx, ny, dx, dy)) {
                            moves.add(new int[]{dx, dy});
                        }
                    }
                }
            } else {
                moves.add(new int[]{nx, ny});
            }
        }
        return moves;
    }

    public static boolean isValidMove(Board board, int playerId, int toX, int toY) {
        Player player = board.getPlayer(playerId);
        if (player.cannotMoveNextTurn()) return false;
        if (!board.isInBounds(toX, toY)) return false;
        for (int[] m : getValidMoves(board, playerId)) {
            if (m[0] == toX && m[1] == toY) return true;
        }
        return false;
    }

    public static boolean isValidWallPlacement(Board board, int playerId, Wall wall) {
        Player player = board.getPlayer(playerId);
        if (player.getWallsRemaining() <= 0) return false;

        int wx = wall.getX(), wy = wall.getY();
        if (wx < 0 || wx > Board.WALL_MAX - 1 || wy < 0 || wy > Board.WALL_MAX - 1) return false;

        if (wallConflicts(board, wall)) return false;
        if (normalWallConflictsWithMiniWalls(board, wall)) return false;

        board.addWall(wall);
        boolean pathsOk = PathFinder.hasPath(board, 1) && PathFinder.hasPath(board, 2);
        board.removeLastWall();

        return pathsOk;
    }

    private static boolean wallConflicts(Board board, Wall newWall) {
        for (Wall existing : board.getWalls()) {
            if (existing.getDirection() == newWall.getDirection()) {
                if (existing.getDirection() == Wall.Direction.HORIZONTAL) {
                    if (existing.getY() == newWall.getY()
                            && Math.abs(existing.getX() - newWall.getX()) <= 1) {
                        return true;
                    }
                } else {
                    if (existing.getX() == newWall.getX()
                            && Math.abs(existing.getY() - newWall.getY()) <= 1) {
                        return true;
                    }
                }
            } else {
                if (existing.getX() == newWall.getX() && existing.getY() == newWall.getY()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isValidRunnerMove(Board board, int playerId, int toX, int toY) {
        Player p = board.getPlayer(playerId);
        Player opponent = board.getOpponent(playerId);

        if (p.getCharacterType() != CharacterType.RUNNER) return false;
        if (p.getSkillRemaining() <= 0) return false;
        if (p.cannotMoveNextTurn()) return false;
        if (!board.isInBounds(toX, toY)) return false;

        int dx = toX - p.getX();
        int dy = toY - p.getY();
        boolean straightTwo = (Math.abs(dx) == 2 && dy == 0) || (Math.abs(dy) == 2 && dx == 0);
        if (!straightTwo) return false;

        int stepX = Integer.compare(dx, 0);
        int stepY = Integer.compare(dy, 0);
        int x1 = p.getX() + stepX;
        int y1 = p.getY() + stepY;
        int x2 = p.getX() + stepX * 2;
        int y2 = p.getY() + stepY * 2;

        if (board.isBlocked(p.getX(), p.getY(), x1, y1)) return false;
        if (board.isBlocked(x1, y1, x2, y2)) return false;

        if ((x1 == opponent.getX() && y1 == opponent.getY())
                || (x2 == opponent.getX() && y2 == opponent.getY())) {
            return false;
        }

        return true;
    }

    public static boolean isValidAcrobatMove(Board board, int playerId, int toX, int toY) {
        Player p = board.getPlayer(playerId);
        Player opponent = board.getOpponent(playerId);

        if (p.getCharacterType() != CharacterType.ACROBAT) return false;
        if (p.getSkillRemaining() <= 0) return false;
        if (p.cannotMoveNextTurn()) return false;
        if (!board.isInBounds(toX, toY)) return false;

        int dx = toX - p.getX();
        int dy = toY - p.getY();
        if (Math.abs(dx) != 1 || Math.abs(dy) != 1) return false;

        if (toX == opponent.getX() && toY == opponent.getY()) return false;

        boolean route1 = !board.isBlocked(p.getX(), p.getY(), p.getX() + dx, p.getY())
                && !board.isBlocked(p.getX() + dx, p.getY(), toX, toY);
        boolean route2 = !board.isBlocked(p.getX(), p.getY(), p.getX(), p.getY() + dy)
                && !board.isBlocked(p.getX(), p.getY() + dy, toX, toY);

        return route1 || route2;
    }

    public static boolean canBreakWall(Board board, int playerId, Wall target) {
        Player p = board.getPlayer(playerId);
        if (p.getCharacterType() != CharacterType.BREAKER) return false;
        if (p.getSkillRemaining() <= 0) return false;
        return board.getWalls().contains(target);
    }

    public static boolean isValidMiniWallPlacement(Board board, int playerId, MiniWall miniWall) {
        Player p = board.getPlayer(playerId);
        if (p.getCharacterType() != CharacterType.BUILDER) return false;
        if (p.getSkillRemaining() <= 0) return false;
        if (p.getMiniWallsRemaining() <= 0) return false;

        if (!isMiniWallInBounds(miniWall)) return false;
        if (miniWallConflicts(board, miniWall)) return false;

        board.addMiniWall(miniWall);
        boolean pathsOk = PathFinder.hasPath(board, 1) && PathFinder.hasPath(board, 2);
        board.removeLastMiniWall();

        return pathsOk;
    }

    public static boolean isValidMiniWallPlacements(Board board, int playerId, List<MiniWall> miniWalls) {
        Player p = board.getPlayer(playerId);
        if (p.getCharacterType() != CharacterType.BUILDER) return false;
        if (p.getSkillRemaining() <= 0) return false;
        if (miniWalls == null || miniWalls.isEmpty() || miniWalls.size() > 2) return false;
        if (p.getMiniWallsRemaining() < miniWalls.size()) return false;

        for (int i = 0; i < miniWalls.size(); i++) {
            MiniWall wall = miniWalls.get(i);
            if (!isMiniWallInBounds(wall)) return false;
            if (miniWallConflicts(board, wall)) return false;
            for (int j = 0; j < i; j++) {
                if (wall.equals(miniWalls.get(j))) return false;
            }
        }

        for (MiniWall wall : miniWalls) board.addMiniWall(wall);
        boolean pathsOk = PathFinder.hasPath(board, 1) && PathFinder.hasPath(board, 2);
        for (int i = 0; i < miniWalls.size(); i++) board.removeLastMiniWall();

        return pathsOk;
    }

    public static boolean isValidTrapPlacement(Board board, int playerId, int x, int y) {
        Player p = board.getPlayer(playerId);
        Player opponent = board.getOpponent(playerId);

        if (p.getCharacterType() != CharacterType.TRAPPER) return false;
        if (p.getTrapRemaining() <= 0) return false;
        if (!board.isInBounds(x, y)) return false;
        if (p.getX() == x && p.getY() == y) return false;
        if (opponent.getX() == x && opponent.getY() == y) return false;
        if (y == 0 || y == Board.SIZE - 1) return false;

        for (Trap trap : board.getTraps()) {
            if (trap.isActive() && trap.getX() == x && trap.getY() == y) return false;
        }

        return true;
    }

    private static boolean isMiniWallInBounds(MiniWall miniWall) {
        int x = miniWall.getX();
        int y = miniWall.getY();
        if (miniWall.getDirection() == MiniWall.Direction.HORIZONTAL) {
            return x >= 0 && x < Board.SIZE && y >= 0 && y < Board.SIZE - 1;
        }
        return x >= 0 && x < Board.SIZE - 1 && y >= 0 && y < Board.SIZE;
    }

    private static boolean miniWallConflicts(Board board, MiniWall miniWall) {
        for (MiniWall existing : board.getMiniWalls()) {
            if (existing.equals(miniWall)) return true;
        }

        for (Wall wall : board.getWalls()) {
            if (normalWallCoversMiniWall(wall, miniWall)) return true;
        }

        return false;
    }

    private static boolean normalWallConflictsWithMiniWalls(Board board, Wall wall) {
        for (MiniWall miniWall : board.getMiniWalls()) {
            if (normalWallCoversMiniWall(wall, miniWall)) return true;
        }
        return false;
    }

    private static boolean normalWallCoversMiniWall(Wall wall, MiniWall miniWall) {
        if (wall.getDirection() == Wall.Direction.HORIZONTAL
                && miniWall.getDirection() == MiniWall.Direction.HORIZONTAL) {
            return wall.getY() == miniWall.getY()
                    && (miniWall.getX() == wall.getX() || miniWall.getX() == wall.getX() + 1);
        }

        if (wall.getDirection() == Wall.Direction.VERTICAL
                && miniWall.getDirection() == MiniWall.Direction.VERTICAL) {
            return wall.getX() == miniWall.getX()
                    && (miniWall.getY() == wall.getY() || miniWall.getY() == wall.getY() + 1);
        }

        return false;
    }

    public static boolean checkWin(Board board, int playerId) {
        Player player = board.getPlayer(playerId);
        return player.getY() == player.getGoalRow();
    }

    private static int[][] perpendicular(int[] dir) {
        if (dir[0] == 0) {
            return new int[][]{{1, 0}, {-1, 0}};
        }
        return new int[][]{{0, 1}, {0, -1}};
    }
}
