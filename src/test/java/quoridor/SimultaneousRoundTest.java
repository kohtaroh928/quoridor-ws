package quoridor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimultaneousRoundTest {
    @Test
    void sameDestinationFailsForBothPlayers() {
        Board board = boardAt(4, 0, 4, 2);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.move(4, 1), SimultaneousRound.Action.move(4, 1)));

        assertArrayEquals(new boolean[]{false, false}, result.success);
        assertPosition(board, 1, 4, 0);
        assertPosition(board, 2, 4, 2);
    }

    @Test
    void swappingPositionsFailsForBothPlayers() {
        Board board = boardAt(4, 4, 4, 5);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.move(4, 5), SimultaneousRound.Action.move(4, 4)));

        assertArrayEquals(new boolean[]{false, false}, result.success);
        assertPosition(board, 1, 4, 4);
        assertPosition(board, 2, 4, 5);
    }

    @Test
    void movingIntoVacatedStartSucceeds() {
        Board board = boardAt(4, 4, 4, 5);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.move(4, 5), SimultaneousRound.Action.move(5, 5)));

        assertArrayEquals(new boolean[]{true, true}, result.success);
        assertPosition(board, 1, 4, 5);
        assertPosition(board, 2, 5, 5);
    }

    @Test
    void wallIsAppliedBeforeMovement() {
        Board board = new Board(2);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.move(4, 1),
                SimultaneousRound.Action.wall(new Wall(4, 0, Wall.Direction.HORIZONTAL))));

        assertFalse(result.success[0]);
        assertTrue(result.success[1]);
        assertPosition(board, 1, 4, 0);
        assertEquals(1, board.getWalls().size());
        assertEquals(9, board.getPlayer(2).getWallsRemaining());
    }

    @Test
    void conflictingWallsBothFailWithoutConsumption() {
        Board board = new Board(2);
        Wall wall = new Wall(3, 3, Wall.Direction.HORIZONTAL);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.wall(wall),
                SimultaneousRound.Action.wall(new Wall(3, 3, Wall.Direction.HORIZONTAL))));

        assertArrayEquals(new boolean[]{false, false}, result.success);
        assertTrue(board.getWalls().isEmpty());
        assertEquals(10, board.getPlayer(1).getWallsRemaining());
        assertEquals(10, board.getPlayer(2).getWallsRemaining());
    }

    @Test
    void jumpFailsWhenOpponentSelectedMove() {
        Board board = boardAt(4, 4, 4, 5);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.move(4, 6), SimultaneousRound.Action.move(5, 5)));

        assertFalse(result.success[0]);
        assertTrue(result.success[1]);
        assertPosition(board, 1, 4, 4);
        assertPosition(board, 2, 5, 5);
    }

    @Test
    void simultaneousGoalsAreDraw() {
        Board board = boardAt(4, 7, 4, 1);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.move(4, 8), SimultaneousRound.Action.move(4, 0)));

        assertEquals(0, result.winner);
        assertArrayEquals(new boolean[]{true, true}, result.success);
    }

    @Test
    void waitCanBeRejectedAfterPreviousWait() {
        Board board = new Board(2);
        assertTrue(SimultaneousRound.isValidInput(board, 1, SimultaneousRound.Action.waitAction(), true));
        assertFalse(SimultaneousRound.isValidInput(board, 1, SimultaneousRound.Action.waitAction(), false));
    }

    // --- 4人対戦のケース ---

    @Test
    void fourPlayerTwoOfFourCollideOnSameCellFailOnlyThosePlayers() {
        // P1(4,0)とP3(3,1)がどちらも(4,1)へ移動しようとして衝突し、両者失敗する。
        // P2(4,8)/P4(8,4)は無関係な単独移動なので成功する。
        Board board = board4At(4, 0, 4, 8, 3, 1, 8, 4);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.move(4, 1),
                SimultaneousRound.Action.move(4, 7),
                SimultaneousRound.Action.move(4, 1),
                SimultaneousRound.Action.move(7, 4)));

        assertFalse(result.success[0]);
        assertTrue(result.success[1]);
        assertFalse(result.success[2]);
        assertTrue(result.success[3]);
        assertPosition(board, 1, 4, 0);
        assertPosition(board, 3, 3, 1);
        assertPosition(board, 2, 4, 7);
        assertPosition(board, 4, 7, 4);
    }

    @Test
    void fourPlayerConflictingWallsFailWithoutConsumption() {
        Board board = new Board(4);
        Wall wall = new Wall(3, 3, Wall.Direction.HORIZONTAL);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.wall(wall),
                SimultaneousRound.Action.wall(new Wall(3, 3, Wall.Direction.HORIZONTAL)),
                SimultaneousRound.Action.waitAction(),
                SimultaneousRound.Action.waitAction()));

        assertFalse(result.success[0]);
        assertFalse(result.success[1]);
        assertTrue(board.getWalls().isEmpty());
        assertEquals(5, board.getPlayer(1).getWallsRemaining());
        assertEquals(5, board.getPlayer(2).getWallsRemaining());
    }

    @Test
    void fourPlayerNonConflictingWallsAllSucceed() {
        Board board = new Board(4);
        SimultaneousRound.Outcome result = SimultaneousRound.resolve(board, actions(
                SimultaneousRound.Action.wall(new Wall(0, 0, Wall.Direction.HORIZONTAL)),
                SimultaneousRound.Action.wall(new Wall(2, 2, Wall.Direction.VERTICAL)),
                SimultaneousRound.Action.wall(new Wall(5, 5, Wall.Direction.HORIZONTAL)),
                SimultaneousRound.Action.wall(new Wall(7, 6, Wall.Direction.VERTICAL))));

        assertArrayEquals(new boolean[]{true, true, true, true}, result.success);
        assertEquals(4, board.getWalls().size());
    }

    private static SimultaneousRound.Action[] actions(SimultaneousRound.Action... actions) {
        return actions;
    }

    private static Board boardAt(int p1x, int p1y, int p2x, int p2y) {
        Board board = new Board(2);
        board.getPlayer(1).setPosition(p1x, p1y);
        board.getPlayer(2).setPosition(p2x, p2y);
        return board;
    }

    private static Board board4At(int p1x, int p1y, int p2x, int p2y, int p3x, int p3y, int p4x, int p4y) {
        Board board = new Board(4);
        board.getPlayer(1).setPosition(p1x, p1y);
        board.getPlayer(2).setPosition(p2x, p2y);
        board.getPlayer(3).setPosition(p3x, p3y);
        board.getPlayer(4).setPosition(p4x, p4y);
        return board;
    }

    private static void assertPosition(Board board, int player, int x, int y) {
        assertEquals(x, board.getPlayer(player).getX());
        assertEquals(y, board.getPlayer(player).getY());
    }
}
