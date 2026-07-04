package quoridor;

public class Trap {
    private final int x;
    private final int y;
    private final int ownerPlayerId;
    private boolean active = true;

    public Trap(int x, int y, int ownerPlayerId) {
        this.x = x;
        this.y = y;
        this.ownerPlayerId = ownerPlayerId;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getOwnerPlayerId() { return ownerPlayerId; }
    public boolean isActive() { return active; }
    public void deactivate() { active = false; }
}
