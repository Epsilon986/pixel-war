package org.pixelwar.pixelwar.domain;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontierTests {
    @Test void incrementalFrontiersMatchIndependentReconstructionAfterMutationsConversionsAndResets() {
        var random = new Random(3102026);
        var conversion = new NeighborConversion();
        for (int width = 1; width <= 12; width++) for (int height = 1; height <= 8; height++) {
            var board = new Board(width, height);
            for (int step = 0; step < 80; step++) {
                if (step % 31 == 0) board.reset();
                int x = random.nextInt(width), y = random.nextInt(height);
                var color = CellState.values()[1 + random.nextInt(4)];
                board.place(x, y, color);
                assertFrontiers(board);
                board.place(x, y, color); // No-op must not create duplicates.
                assertFrontiers(board);
                if (step % 3 == 0) conversion.convert(board);
                assertFrontiers(board);
            }
        }
    }

    @Test void samplingRemovesStaleCellsAndBoardsAreIndependent() {
        var first = new Board(3, 1);
        var second = new Board(3, 1);
        first.place(0, 0, CellState.RED);
        assertEquals(1, first.frontierSize(CellState.RED));
        assertEquals(0, second.frontierSize(CellState.RED));
        assertEquals(new PlacementStrategy.Position(1, 0), first.randomFrontierPosition(CellState.RED, new Random(1)));
        first.place(1, 0, CellState.RED);
        assertFalse(first.isFrontier(1, 0, CellState.RED));
        assertEquals(new PlacementStrategy.Position(2, 0), first.randomFrontierPosition(CellState.RED, new Random(1)));
        first.place(2, 0, CellState.RED);
        assertEquals(0, first.frontierSize(CellState.RED));
        assertNull(first.randomFrontierPosition(CellState.RED, new Random(1)));
        first.reset();
        assertEquals(0, first.frontierMetrics().updates());
        assertThrows(IllegalArgumentException.class, () -> first.frontierSize(CellState.EMPTY));
    }

    @Test void repeatedConversionPassesKeepFrontiersCoherent() {
        var board = new Board(9, 9);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) {
            board.place(x, y, (x + y) % 2 == 0 ? CellState.RED : CellState.BLUE);
        }
        var conversion = new NeighborConversion();
        for (int i = 0; i < 10; i++) {
            conversion.convert(board);
            assertFrontiers(board);
        }
    }

    private void assertFrontiers(Board board) {
        for (var color : CellState.values()) if (color != CellState.EMPTY) {
            int count = 0;
            for (int y = 0; y < board.height(); y++) for (int x = 0; x < board.width(); x++) {
                boolean expected = board.get(x, y) != color && (
                        x > 0 && board.get(x - 1, y) == color || x + 1 < board.width() && board.get(x + 1, y) == color
                        || y > 0 && board.get(x, y - 1) == color || y + 1 < board.height() && board.get(x, y + 1) == color);
                assertEquals(expected, board.isFrontier(x, y, color), color + " at " + x + "," + y);
                if (expected) count++;
            }
            assertEquals(count, board.frontierSize(color));
            if (count > 0) {
                var sampled = board.randomFrontierPosition(color, new Random(42));
                assertTrue(board.isFrontier(sampled.x(), sampled.y(), color));
            }
        }
    }
}
