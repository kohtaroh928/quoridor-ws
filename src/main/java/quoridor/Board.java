package quoridor;

import java.util.ArrayList;
import java.util.List;

public class Board {

    public static final int SIZE = 9;
    public static final int WALL_MAX = 8;

    private final Player[] players;
    private final List<Wall> walls;
    private final List<MiniWall> miniWalls;
    private final List<Trap> traps;
    private final List<Wall> brokenWalls;

    public Board() {
        this(2);
    }

    public Board(int playerCount) {
        this.players = createPlayers(playerCount);
        walls = new ArrayList<>();
        miniWalls = new ArrayList<>();
        traps = new ArrayList<>();
        brokenWalls = new ArrayList<>();
    }

    private static Player[] createPlayers(int playerCount) {
        if (playerCount == 4) {
            return new Player[]{
                new Player(1, 4, 0, 5),
                new Player(2, 4, 8, 5),
                new Player(3, 0, 4, 5),
                new Player(4, 8, 4, 5),
            };
        }
        return new Player[]{
            new Player(1, 4, 0, 10),
            new Player(2, 4, 8, 10),
        };
    }

    public int getPlayerCount() {
        return players.length;
    }

    public Player getPlayer(int id) {
        return players[id - 1];
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

    public List<Wall> getBrokenWalls() {
        return brokenWalls;
    }

    public void addWall(Wall wall) {
        walls.add(wall);
    }

    public boolean removeWall(Wall wall) {
        return walls.remove(wall);
    }

    // 壁を破壊した跡を記録する。その場所には二度と壁を置けなくする
    public void addBrokenWall(Wall wall) {
        brokenWalls.add(wall);
    }

    public boolean isBrokenWallSlot(Wall wall) {
        return brokenWalls.contains(wall);
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

    // 指定座標にいるプレイヤーのidを返す(いなければ0)
    public int getPlayerAt(int x, int y) {
        for (Player p : players) {
            if (p.getX() == x && p.getY() == y) return p.getId();
        }
        return 0;
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
        Player[] fresh = createPlayers(players.length);
        for (int i = 0; i < players.length; i++) {
            players[i].reset(fresh[i].getX(), fresh[i].getY());
        }
        walls.clear();
        miniWalls.clear();
        traps.clear();
        brokenWalls.clear();
    }
}
