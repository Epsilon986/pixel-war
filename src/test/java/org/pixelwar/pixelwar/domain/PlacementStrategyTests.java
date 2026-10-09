package org.pixelwar.pixelwar.domain;

import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlacementStrategyTests {
    private final Simulation.Player red = new Simulation.Player(1, "red", CellState.RED, 1);

    @Test void emptyFrontierAndFullExplorationUseUniformBoardCoordinates() {
        for (double probability : new double[]{0, 1}) {
            var board = new Board(5, 3);
            if (probability == 1) board.place(2, 1, CellState.RED);
            var random = mock(java.util.random.RandomGenerator.class);
            when(random.nextDouble()).thenReturn(0.5);
            when(random.nextInt(5)).thenReturn(4);
            when(random.nextInt(3)).thenReturn(2);
            var strategy = new FrontierPlacementStrategy(random, probability, 10);
            assertEquals(new PlacementStrategy.Position(4, 2), strategy.choose(board, red));
            verify(random).nextInt(5);
            verify(random).nextInt(3);
        }
    }

    @Test void zeroExplorationUsesFrontierAndUpdatesBetweenSequentialPoses() {
        var board = new Board(15, 15);
        board.place(7, 7, CellState.RED);
        var strategy = new FrontierPlacementStrategy(new Random(123), 0, 10);
        for (int i = 0; i < 50; i++) {
            var position = strategy.choose(board, red);
            assertTrue(board.isFrontier(position.x(), position.y(), CellState.RED));
            assertTrue(board.placeForPlayer(position.x(), position.y(), CellState.RED));
            assertFalse(board.isFrontier(position.x(), position.y(), CellState.RED));
        }
    }

    @Test void scoreCountsOnlyCreatedNeighborConversionsAndDoesNotMutateBoard() {
        var board = new Board(5, 5);
        board.place(1, 2, CellState.RED); board.place(3, 2, CellState.RED); board.place(2, 3, CellState.RED);
        board.place(2, 2, CellState.BLUE);
        var strategy = new FrontierPlacementStrategy(new Random(1), 0, 10);
        var target = new PlacementStrategy.Position(2, 1);
        assertEquals(8, strategy.score(board, target, CellState.RED));
        board.place(2, 0, CellState.RED);
        assertEquals(10, strategy.score(board, target, CellState.RED));
        board.place(2, 1, CellState.BLUE);
        var counts = board.counts();
        var maintenance = board.frontierMetrics();
        assertEquals(11, strategy.score(board, target, CellState.RED));
        assertEquals(counts, board.counts());
        assertEquals(maintenance, board.frontierMetrics());
        assertEquals(CellState.BLUE, board.get(2, 1));
        var surrounded = new Board(3, 3);
        NeighborConversionTests.surround(surrounded, CellState.RED);
        assertEquals(8, strategy.score(surrounded, new PlacementStrategy.Position(1, 1), CellState.RED));
    }

    @Test void selectionKeepsBestScoreAmongSampledCandidates() {
        var board = new Board(5, 5);
        board.place(1, 2, CellState.RED); board.place(3, 2, CellState.RED); board.place(2, 3, CellState.RED);
        var random = mock(java.util.random.RandomGenerator.class);
        var strategy = new FrontierPlacementStrategy(random, 0, board.frontierSize(CellState.RED));
        when(random.nextDouble()).thenReturn(0.5);
        when(random.nextInt(anyInt())).thenAnswer(call -> 0);
        int[] next = {0};
        int size = board.frontierSize(CellState.RED);
        // Each frontier slot is sampled once; tie-breaking uses bounds <= the number of samples so far.
        when(random.nextInt(size)).thenAnswer(call -> next[0]++ % size);
        int best = Integer.MIN_VALUE;
        var enumerator = mock(java.util.random.RandomGenerator.class);
        for (int i = 0; i < size; i++) {
            when(enumerator.nextInt(size)).thenReturn(i);
            best = Math.max(best, strategy.score(board, board.randomFrontierPosition(CellState.RED, enumerator), CellState.RED));
        }
        var selected = strategy.choose(board, red);
        assertEquals(best, strategy.score(board, selected, CellState.RED));
    }

    @Test void tiesAreRandomizedAndFixedSeedsAreReproducible() {
        var board = new Board(3, 3);
        board.place(1, 1, CellState.RED);
        var first = new FrontierPlacementStrategy(new Random(71), 0, 10);
        var second = new FrontierPlacementStrategy(new Random(71), 0, 10);
        var selected = new HashSet<PlacementStrategy.Position>();
        for (int i = 0; i < 100; i++) {
            var position = first.choose(board, red);
            assertEquals(position, second.choose(board, red));
            selected.add(position);
        }
        assertEquals(4, selected.size());
    }

    @Test void randomReferenceSelectsEmptyCellsBeforeReplacingFullBoard() {
        var board = new Board(4, 4);
        var strategy = new RandomPlacementStrategy(new Random(42));
        for (int i = 0; i < 16; i++) {
            var position = strategy.choose(board, red);
            assertEquals(CellState.EMPTY, board.get(position.x(), position.y()));
            board.place(position.x(), position.y(), CellState.RED);
        }
        var position = strategy.choose(board, red);
        assertEquals(CellState.RED, board.get(position.x(), position.y()));
    }
}
