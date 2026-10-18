package org.pixelwar.pixelwar.domain;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeakOpponentBonusTests {
    private final Simulation.Player red = new Simulation.Player(1, "red", CellState.RED, 1);

    private Board board() {
        var board = new Board(9, 1);
        board.place(0, 0, CellState.RED);
        board.place(1, 0, CellState.BLUE);
        for (int x = 2; x <= 5; x++) board.place(x, 0, CellState.GREEN);
        return board;
    }

    @Test void bonusRewardsWeakEnemyAndPriorityButNotEmptyOrAlliedCells() {
        var board = board();
        var strategy = new FrontierPlacementStrategy(new Random(42), 0, 10, 8, 20);
        strategy.beginAction(board, red);
        assertEquals(CellState.BLUE, strategy.priorityTarget(board, CellState.RED));
        var weak = new PlacementStrategy.Position(1, 0);
        assertEquals(strategy.score(board, weak, CellState.RED) + 6 + 8, strategy.conquestScore(board, weak, CellState.RED));
        for (int x : new int[]{0, 2, 8}) {
            var position = new PlacementStrategy.Position(x, 0);
            assertEquals(strategy.score(board, position, CellState.RED), strategy.conquestScore(board, position, CellState.RED));
        }
    }

    @Test void prioritySurvivesPopulationFluctuationsForConfiguredNumberOfActions() {
        var board = board();
        var strategy = new FrontierPlacementStrategy(new Random(42), 0, 10, 8, 3);
        strategy.beginAction(board, red);
        board.place(2, 0, CellState.BLUE); board.place(3, 0, CellState.BLUE);
        for (int i = 0; i < 2; i++) {
            strategy.beginAction(board, red);
            for (int n = 0; n < 10; n++) strategy.choose(board, red);
            assertEquals(CellState.BLUE, strategy.priorityTarget(board, CellState.RED));
        }
        strategy.beginAction(board, red);
        assertEquals(CellState.GREEN, strategy.priorityTarget(board, CellState.RED));
    }

    @Test void eliminatedTargetIsReplacedImmediatelyAndResetAndBoardsAreIndependent() {
        var first = board();
        var second = new Board(2, 1);
        second.place(0, 0, CellState.RED); second.place(1, 0, CellState.YELLOW);
        var strategy = new FrontierPlacementStrategy(new Random(42), 0, 10, 8, 20);
        strategy.beginAction(first, red); strategy.beginAction(second, red);
        assertEquals(CellState.BLUE, strategy.priorityTarget(first, CellState.RED));
        assertEquals(CellState.YELLOW, strategy.priorityTarget(second, CellState.RED));
        first.place(1, 0, CellState.RED);
        strategy.beginAction(first, red);
        assertEquals(CellState.GREEN, strategy.priorityTarget(first, CellState.RED));
        strategy.reset(first);
        assertNull(strategy.priorityTarget(first, CellState.RED));
        assertEquals(CellState.YELLOW, strategy.priorityTarget(second, CellState.RED));
        second.place(1, 0, CellState.RED);
        strategy.beginAction(second, red);
        assertNull(strategy.priorityTarget(second, CellState.RED));
    }

    @Test void zeroBonusPreservesOriginalScoreAndDoesNotKeepTargets() {
        var board = board();
        var strategy = new FrontierPlacementStrategy(new Random(42), 0, 10, 0, 20);
        strategy.beginAction(board, red);
        assertNull(strategy.priorityTarget(board, CellState.RED));
        for (int x = 0; x < 9; x++) {
            var target = new PlacementStrategy.Position(x, 0);
            assertEquals(strategy.score(board, target, CellState.RED), strategy.conquestScore(board, target, CellState.RED));
        }
    }

    @Test void weakOpponentWinsSelectionWhenBothEnemiesAreSampled() {
        var board = new Board(4, 1);
        board.place(0, 0, CellState.BLUE); board.place(1, 0, CellState.RED);
        board.place(2, 0, CellState.GREEN); board.place(3, 0, CellState.GREEN);
        var random = new Random(42) {
            private int slot;
            @Override public int nextInt(int bound) { return slot++ % bound; }
        };
        var strategy = new FrontierPlacementStrategy(random, 0, 2, 8, 20);
        strategy.beginAction(board, red);
        assertEquals(new PlacementStrategy.Position(0, 0), strategy.choose(board, red));
    }
}
