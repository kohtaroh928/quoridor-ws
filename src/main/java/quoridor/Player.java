package quoridor;

public class Player {

    private final int id;
    private int x;
    private int y;
    private int wallsRemaining;

    public Player(int id, int x, int y) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.wallsRemaining = 10;
    }

    public int getId() { return id; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWallsRemaining() { return wallsRemaining; }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void useWall() {
        wallsRemaining--;
    }

    public int getGoalRow() {
        return id == 1 ? 8 : 0;
    }

    public void reset(int startX, int startY) {
        this.x = startX;
        this.y = startY;
        this.wallsRemaining = 10;
    }
}
