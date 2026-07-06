package quoridor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GameLogic {

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    // 各プレイヤーのゴール: P1→上辺(y=8), P2→下辺(y=0), P3→右辺(x=8), P4→左辺(x=0)
    public static boolean isGoal(int playerId, int x, int y) {
        switch (playerId) {
            case 1: return y == 8;
            case 2: return y == 0;
            case 3: return x == 8;
            default: return x == 0;
        }
    }

    public static List<int[]> getValidMoves(Board board, int playerId) {
        List<int[]> moves = new ArrayList<>();
        Player player = board.getPlayer(playerId);
        if (player.cannotMoveNextTurn()) return moves;

        int px = player.getX(), py = player.getY();

        for (int[] d : DIRS) {
            int nx = px + d[0];
            int ny = py + d[1];

            if (!board.isInBounds(nx, ny)) continue;
            if (board.isBlocked(px, py, nx, ny)) continue;

            if (board.getPlayerAt(nx, ny) != 0) {
                int jx = nx + d[0];
                int jy = ny + d[1];

                if (board.isInBounds(jx, jy) && !board.isBlocked(nx, ny, jx, jy)
                        && board.getPlayerAt(jx, jy) == 0) {
                    moves.add(new int[]{jx, jy});
                } else {
                    for (int[] pd : perpendicular(d)) {
                        int dx = nx + pd[0];
                        int dy = ny + pd[1];
                        if (board.isInBounds(dx, dy) && !board.isBlocked(nx, ny, dx, dy)
                                && board.getPlayerAt(dx, dy) == 0) {
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
        return isPlaceableWall(board, wall);
    }

    // プレイヤーの残り壁数に関係なく、壁自体が置けるか(範囲内・衝突なし・全員の経路が残るか)を判定する
    private static boolean isPlaceableWall(Board board, Wall wall) {
        int wx = wall.getX(), wy = wall.getY();
        if (wx < 0 || wx > Board.WALL_MAX - 1 || wy < 0 || wy > Board.WALL_MAX - 1) return false;

        if (wallConflicts(board, wall)) return false;
        if (normalWallConflictsWithMiniWalls(board, wall)) return false;

        board.addWall(wall);
        boolean pathsOk = allPlayersHavePath(board);
        board.removeLastWall();

        return pathsOk;
    }

    // 障害物モード用: 盤面中央6x6(壁座標1〜6)を4分割し、各象限に同数の壁をランダム配置する。
    // 象限ごとに同数を割り当てることで、盤のどの辺からも均等な距離を保つ。
    private static final int[][] OBSTACLE_QUADRANTS = {
        {1, 3, 1, 3}, {4, 6, 1, 3}, {1, 3, 4, 6}, {4, 6, 4, 6}
    };
    private static final int OBSTACLE_WALLS_PER_QUADRANT = 2;
    private static final int OBSTACLE_MAX_ATTEMPTS = 60;

    public static void placeObstacleWalls(Board board, Random random) {
        for (int[] quadrant : OBSTACLE_QUADRANTS) {
            for (int i = 0; i < OBSTACLE_WALLS_PER_QUADRANT; i++) {
                placeRandomWallInQuadrant(board, random, quadrant);
            }
        }
    }

    private static void placeRandomWallInQuadrant(Board board, Random random, int[] quadrant) {
        for (int attempt = 0; attempt < OBSTACLE_MAX_ATTEMPTS; attempt++) {
            int x = quadrant[0] + random.nextInt(quadrant[1] - quadrant[0] + 1);
            int y = quadrant[2] + random.nextInt(quadrant[3] - quadrant[2] + 1);
            Wall.Direction dir = random.nextBoolean() ? Wall.Direction.HORIZONTAL : Wall.Direction.VERTICAL;
            Wall wall = new Wall(x, y, dir);
            if (obstacleWallTooClose(board, wall)) continue;
            if (isPlaceableWall(board, wall)) {
                board.addWall(wall);
                return;
            }
        }
    }

    // 同じ向きの壁が端同士でつながって一枚の長い壁になるのを防ぐ(通常の衝突判定は
    // 間隔2マスの端接続を許すため、障害物モードではさらに1マス分の余白を要求する)
    private static boolean obstacleWallTooClose(Board board, Wall wall) {
        for (Wall existing : board.getWalls()) {
            if (existing.getDirection() != wall.getDirection()) continue;
            if (wall.getDirection() == Wall.Direction.HORIZONTAL) {
                if (existing.getY() == wall.getY() && Math.abs(existing.getX() - wall.getX()) <= 2) return true;
            } else {
                if (existing.getX() == wall.getX() && Math.abs(existing.getY() - wall.getY()) <= 2) return true;
            }
        }
        return false;
    }

    private static boolean allPlayersHavePath(Board board) {
        for (int p = 1; p <= board.getPlayerCount(); p++) {
            if (!PathFinder.hasPath(board, p)) return false;
        }
        return true;
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

        if (board.getPlayerAt(x1, y1) != 0 || board.getPlayerAt(x2, y2) != 0) {
            return false;
        }

        return true;
    }

    public static boolean isValidAcrobatMove(Board board, int playerId, int toX, int toY) {
        Player p = board.getPlayer(playerId);

        if (p.getCharacterType() != CharacterType.ACROBAT) return false;
        if (p.getSkillRemaining() <= 0) return false;
        if (p.cannotMoveNextTurn()) return false;
        if (!board.isInBounds(toX, toY)) return false;

        int dx = toX - p.getX();
        int dy = toY - p.getY();
        if (Math.abs(dx) != 1 || Math.abs(dy) != 1) return false;

        if (board.getPlayerAt(toX, toY) != 0) return false;

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
        boolean pathsOk = allPlayersHavePath(board);
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
        boolean pathsOk = allPlayersHavePath(board);
        for (int i = 0; i < miniWalls.size(); i++) board.removeLastMiniWall();

        return pathsOk;
    }

    public static boolean isValidTrapPlacement(Board board, int playerId, int x, int y) {
        Player p = board.getPlayer(playerId);

        if (p.getCharacterType() != CharacterType.TRAPPER) return false;
        if (p.getTrapRemaining() <= 0) return false;
        if (!board.isInBounds(x, y)) return false;
        if (board.getPlayerAt(x, y) != 0) return false;
        if (y == 0 || y == Board.SIZE - 1) return false;
        // 4人戦では左右の辺もゴールになるため、全ての端を禁止する
        if (board.getPlayerCount() == 4 && (x == 0 || x == Board.SIZE - 1)) return false;

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
        return isGoal(playerId, player.getX(), player.getY());
    }

    private static int[][] perpendicular(int[] dir) {
        if (dir[0] == 0) {
            return new int[][]{{1, 0}, {-1, 0}};
        }
        return new int[][]{{0, 1}, {0, -1}};
    }
}
