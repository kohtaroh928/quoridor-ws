package quoridor;

public class Wall {

    public enum Direction {
        HORIZONTAL, VERTICAL
    }

    private final int x;
    private final int y;
    private final Direction direction;

    public Wall(int x, int y, Direction direction) {
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
        if (!(o instanceof Wall)) return false;
        Wall w = (Wall) o;
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
