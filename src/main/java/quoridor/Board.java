package quoridor;

import java.util.ArrayList;
import java.util.List;

public class Board {

    public static final int SIZE = 9;
    public static final int WALL_MAX = 8;

    private final Player player1;
    private final Player player2;
    private final List<Wall> walls;
    private final List<MiniWall> miniWalls;
    private final List<Trap> traps;

    public Board() {
        player1 = new Player(1, 4, 0);
        player2 = new Player(2, 4, 8);
        walls = new ArrayList<>();
        miniWalls = new ArrayList<>();
        traps = new ArrayList<>();
    }

    public Player getPlayer(int id) {
        return id == 1 ? player1 : player2;
    }

    public Player getOpponent(int playerId) {
        return playerId == 1 ? player2 : player1;
    }

    public List<Wall> getWalls() {
        return walls;
    }

    public List<MiniWall> getMiniWalls() {
        return miniWalls;
    }

    public List<Trap> getTraps() {
        return traps;
    }

    public void addWall(Wall wall) {
        walls.add(wall);
    }

    public boolean removeWall(Wall wall) {
        return walls.remove(wall);
    }

    public void removeLastWall() {
        walls.remove(walls.size() - 1);
    }

    public void addMiniWall(MiniWall wall) {
        miniWalls.add(wall);
    }

    public void removeLastMiniWall() {
        miniWalls.remove(miniWalls.size() - 1);
    }

    public void addTrap(Trap trap) {
        traps.add(trap);
    }

    public Trap findTriggeredTrap(int playerId, int x, int y) {
        for (Trap trap : traps) {
            if (trap.isActive()
                    && trap.getOwnerPlayerId() != playerId
                    && trap.getX() == x
                    && trap.getY() == y) {
                return trap;
            }
        }
        return null;
    }

    public boolean isBlocked(int x1, int y1, int x2, int y2) {
        int dx = x2 - x1;
        int dy = y2 - y1;

        if (dy == 1 && dx == 0) {
            return hasHorizontalWall(x1, y1) || hasMiniHorizontalWall(x1, y1);
        } else if (dy == -1 && dx == 0) {
            return hasHorizontalWall(x1, y1 - 1) || hasMiniHorizontalWall(x1, y1 - 1);
        } else if (dx == 1 && dy == 0) {
            return hasVerticalWall(x1, y1) || hasMiniVerticalWall(x1, y1);
        } else if (dx == -1 && dy == 0) {
            return hasVerticalWall(x1 - 1, y1) || hasMiniVerticalWall(x1 - 1, y1);
        }
        return true;
    }

    private boolean hasHorizontalWall(int x, int y) {
        for (Wall w : walls) {
            if (w.getDirection() == Wall.Direction.HORIZONTAL
                    && w.getY() == y
                    && (w.getX() == x || w.getX() == x - 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMiniHorizontalWall(int x, int y) {
        for (MiniWall w : miniWalls) {
            if (w.getDirection() == MiniWall.Direction.HORIZONTAL
                    && w.getX() == x
                    && w.getY() == y) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMiniVerticalWall(int x, int y) {
        for (MiniWall w : miniWalls) {
            if (w.getDirection() == MiniWall.Direction.VERTICAL
                    && w.getX() == x
                    && w.getY() == y) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVerticalWall(int x, int y) {
        for (Wall w : walls) {
            if (w.getDirection() == Wall.Direction.VERTICAL
                    && w.getX() == x
                    && (w.getY() == y || w.getY() == y - 1)) {
                return true;
            }
        }
        return false;
    }

    public boolean isInBounds(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    public void reset() {
        player1.reset(4, 0);
        player2.reset(4, 8);
        walls.clear();
        miniWalls.clear();
        traps.clear();
    }
}
