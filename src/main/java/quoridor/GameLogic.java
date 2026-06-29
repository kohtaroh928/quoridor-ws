package quoridor;

import java.util.ArrayList;
import java.util.List;

public class GameLogic {

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    public static List<int[]> getValidMoves(Board board, int playerId) {
        List<int[]> moves = new ArrayList<>();
        Player player = board.getPlayer(playerId);
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
