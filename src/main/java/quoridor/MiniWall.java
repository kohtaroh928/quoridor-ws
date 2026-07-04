package quoridor;

public class MiniWall {

    public enum Direction {
        HORIZONTAL, VERTICAL
    }

    private final int x;
    private final int y;
    private final Direction direction;

    public MiniWall(int x, int y, Direction direction) {
        this.x = x;
        this.y = y;
        this.direction = direction;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public Direction getDirection() { return direction; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MiniWall)) return false;
        MiniWall w = (MiniWall) o;
        return x == w.x && y == w.y && direction == w.direction;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * x + y) + direction.ordinal();
    }

    @Override
    public String toString() {
        return direction + "(" + x + "," + y + ")";
    }
}
