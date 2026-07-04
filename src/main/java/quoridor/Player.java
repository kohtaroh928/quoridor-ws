package quoridor;

public class Player {

    private final int id;
    private int x;
    private int y;
    private int wallsRemaining;
    private CharacterType characterType = CharacterType.NONE;
    private int skillRemaining = 0;
    private int miniWallsRemaining = 0;
    private int trapRemaining = 0;
    private boolean cannotMoveNextTurn = false;

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
    public CharacterType getCharacterType() { return characterType; }
    public int getSkillRemaining() { return skillRemaining; }
    public int getMiniWallsRemaining() { return miniWallsRemaining; }
    public int getTrapRemaining() { return trapRemaining; }
    public boolean cannotMoveNextTurn() { return cannotMoveNextTurn; }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void useWall() {
        wallsRemaining--;
    }

    public void useSkill() {
        skillRemaining--;
    }

    public void useMiniWalls(int count) {
        miniWallsRemaining -= count;
    }

    public void useTrap() {
        trapRemaining--;
    }

    public void setCannotMoveNextTurn(boolean value) {
        cannotMoveNextTurn = value;
    }

    public void setCharacter(CharacterType characterType) {
        this.characterType = characterType == null ? CharacterType.NONE : characterType;

        switch (this.characterType) {
            case BREAKER:
                this.skillRemaining = 1;
                this.miniWallsRemaining = 0;
                this.trapRemaining = 0;
                this.wallsRemaining = 10;
                break;
            case ACROBAT:
                this.skillRemaining = 3;
                this.miniWallsRemaining = 0;
                this.trapRemaining = 0;
                this.wallsRemaining = 10;
                break;
            case RUNNER:
                this.skillRemaining = 2;
                this.miniWallsRemaining = 0;
                this.trapRemaining = 0;
                this.wallsRemaining = 10;
                break;
            case BUILDER:
                this.skillRemaining = 2;
                this.miniWallsRemaining = 4;
                this.trapRemaining = 0;
                this.wallsRemaining = 8;
                break;
            case TRAPPER:
                this.skillRemaining = 0;
                this.miniWallsRemaining = 0;
                this.trapRemaining = 1;
                this.wallsRemaining = 10;
                break;
            case NONE:
            default:
                this.skillRemaining = 0;
                this.miniWallsRemaining = 0;
                this.trapRemaining = 0;
                this.wallsRemaining = 10;
                break;
        }

        this.cannotMoveNextTurn = false;
    }

    public int getGoalRow() {
        return id == 1 ? 8 : 0;
    }

    public void reset(int startX, int startY) {
        this.x = startX;
        this.y = startY;
        setCharacter(this.characterType);
    }
}
