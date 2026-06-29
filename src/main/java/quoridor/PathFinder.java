package quoridor;

import java.util.LinkedList;
import java.util.Queue;

public class PathFinder {

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    public static boolean hasPath(Board board, int playerId) {
        Player player = board.getPlayer(playerId);
        int goalRow = player.getGoalRow();

        boolean[][] visited = new boolean[Board.SIZE][Board.SIZE];
        Queue<int[]> queue = new LinkedList<>();

        queue.add(new int[]{player.getX(), player.getY()});
        visited[player.getX()][player.getY()] = true;

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            if (pos[1] == goalRow) return true;

            for (int[] d : DIRS) {
                int nx = pos[0] + d[0];
                int ny = pos[1] + d[1];

                if (board.isInBounds(nx, ny) && !visited[nx][ny]
                        && !board.isBlocked(pos[0], pos[1], nx, ny)) {
                    visited[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        return false;
    }
}
