package quoridor;

import java.util.ArrayList;
import java.util.List;

public class Board {

    public static final int SIZE = 9;
    public static final int WALL_MAX = 8;

    private final Player player1;
    private final Player player2;
    private final List<Wall> walls;

    public Board() {
        player1 = new Player(1, 4, 0);
        player2 = new Player(2, 4, 8);
        walls = new ArrayList<>();
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

    public void addWall(Wall wall) {
        walls.add(wall);
    }

    public void removeLastWall() {
        walls.remove(walls.size() - 1);
    }

    public boolean isBlocked(int x1, int y1, int x2, int y2) {
        int dx = x2 - x1;
        int dy = y2 - y1;

        if (dy == 1 && dx == 0) {
            return hasHorizontalWall(x1, y1);
        } else if (dy == -1 && dx == 0) {
            return hasHorizontalWall(x1, y1 - 1);
        } else if (dx == 1 && dy == 0) {
            return hasVerticalWall(x1, y1);
        } else if (dx == -1 && dy == 0) {
            return hasVerticalWall(x1 - 1, y1);
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
    }
}
