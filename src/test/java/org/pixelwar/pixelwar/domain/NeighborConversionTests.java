package org.pixelwar.pixelwar.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeighborConversionTests {
    private final NeighborConversion conversion = new NeighborConversion();

    @Test void sparseChangesQueueOnlyUniqueAffectedInteriorCells() {
        var board = new Board(1000, 1000);
        assertEquals(0, board.conversionCandidateCount());
        board.place(500, 500, CellState.RED);
        board.place(500, 500, CellState.BLUE);
        board.place(500, 500, CellState.BLUE);
        assertEquals(5, board.conversionCandidateCount());
        conversion.convert(board);
        assertEquals(0, board.conversionCandidateCount());
        board.place(1, 0, CellState.RED);
        assertEquals(1, board.conversionCandidateCount());
        board.reset();
        assertEquals(0, board.conversionCandidateCount());
        assertTrue(conversion.convert(board).values().stream().allMatch(n -> n == 0));
    }

    @Test void repeatedDetectionDoesNotConsumeCandidatesAndBoardsRemainIndependent() {
        var first = new Board(3, 3);
        var second = new Board(3, 3);
        surround(first, CellState.RED);
        surround(second, CellState.BLUE);
        var changes = conversion.detect(first);
        assertEquals(1, changes.size());
        assertEquals(changes, conversion.detect(first));
        conversion.convert(second);
        conversion.convert(first);
        assertEquals(CellState.RED, first.get(1, 1));
        assertEquals(CellState.BLUE, second.get(1, 1));
    }

    @Test void successivePassesWithoutPlacementsMatchFullScanIncludingOscillations() {
        var incremental = checkerboard(11);
        var reference = checkerboard(11);
        for (int pass = 0; pass < 20; pass++) assertSamePass(reference, incremental);
    }

    @Test void randomizedMutationsAndResetsMatchFullScan() {
        var random = new Random(20261009L);
        for (int scenario = 0; scenario < 20; scenario++) {
            int width = 1 + random.nextInt(18), height = 1 + random.nextInt(18);
            var incremental = new Board(width, height);
            var reference = new Board(width, height);
            for (int pass = 0; pass < 100; pass++) {
                if (pass % 29 == 0) { incremental.reset(); reference.reset(); }
                for (int n = random.nextInt(30); n > 0; n--) {
                    int x = random.nextInt(width), y = random.nextInt(height);
                    var color = CellState.values()[1 + random.nextInt(4)];
                    incremental.place(x, y, color);
                    reference.place(x, y, color);
                }
                assertSamePass(reference, incremental);
            }
        }
    }

    private void assertSamePass(Board reference, Board incremental) {
        var expected = new ArrayList<NeighborConversion.Change>();
        for (int y = 1; y < reference.height() - 1; y++) for (int x = 1; x < reference.width() - 1; x++) {
            var color = reference.get(x, y - 1);
            if (color != CellState.EMPTY && reference.get(x, y) != color
                    && reference.get(x, y + 1) == color && reference.get(x - 1, y) == color && reference.get(x + 1, y) == color) {
                expected.add(new NeighborConversion.Change(x, y, color));
            }
        }
        assertEquals(new HashSet<>(expected), new HashSet<>(conversion.detect(incremental)));
        assertEquals(conversion.apply(reference, expected), conversion.convert(incremental));
        assertEquals(reference.counts(), incremental.counts());
        for (int y = 0; y < reference.height(); y++) for (int x = 0; x < reference.width(); x++) {
            assertEquals(reference.get(x, y), incremental.get(x, y));
        }
    }

    static void surround(Board board, CellState color) {
        board.place(1, 0, color); board.place(1, 2, color);
        board.place(0, 1, color); board.place(2, 1, color);
    }
    @Test void convertsEnemyAndEmptyButNotAlreadyMatchingCell() {
        for (var center : new CellState[]{CellState.EMPTY, CellState.BLUE, CellState.RED}) {
            var board = new Board(3, 3);
            surround(board, CellState.RED);
            if (center != CellState.EMPTY) board.place(1, 1, center);
            var result = conversion.convert(board);
            assertEquals(CellState.RED, board.get(1, 1));
            assertEquals(center == CellState.RED ? 0L : 1L, result.get(CellState.RED));
        }
    }
    @Test void requiresFourDirectNonEmptyNeighbors() {
        var board = new Board(3, 3);
        board.place(0, 0, CellState.RED); board.place(2, 0, CellState.RED);
        board.place(0, 2, CellState.RED); board.place(2, 2, CellState.RED);
        assertTrue(conversion.detect(board).isEmpty());
        surround(board, CellState.RED);
        board.place(1, 0, CellState.BLUE);
        assertTrue(conversion.detect(board).isEmpty());
        board.reset();
        board.place(0, 1, CellState.RED); board.place(2, 1, CellState.RED); board.place(1, 0, CellState.RED);
        assertTrue(conversion.detect(board).isEmpty());
    }
    @Test void neverConvertsEdgesOrTinyBoards() {
        for (int width = 1; width <= 3; width++) {
            var board = new Board(width, 2);
            for (int y = 0; y < 2; y++) for (int x = 0; x < width; x++) board.place(x, y, CellState.RED);
            board.place(0, 0, CellState.BLUE);
            assertTrue(conversion.detect(board).isEmpty());
            assertEquals(CellState.BLUE, board.get(0, 0));
        }
        var board = new Board(3, 3);
        surround(board, CellState.RED);
        conversion.convert(board);
        assertEquals(CellState.EMPTY, board.get(0, 0));
    }
    @Test void detectionIsImmutableAndApplicationOrderIndependent() {
        var first = checkerboard(7);
        var second = checkerboard(7);
        var changes = conversion.detect(first);
        assertEquals(CellState.RED, first.get(3, 3));
        var reversed = new ArrayList<>(changes);
        Collections.reverse(reversed);
        conversion.apply(first, changes);
        conversion.apply(second, reversed);
        for (int y = 0; y < 7; y++) for (int x = 0; x < 7; x++) assertEquals(first.get(x, y), second.get(x, y));
        assertEquals(CellState.BLUE, first.get(3, 3));
        // All decisions used the original checkerboard. A second pass is deferred.
        conversion.convert(first);
        assertEquals(CellState.RED, first.get(3, 3));
    }
    private Board checkerboard(int size) {
        var board = new Board(size, size);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) board.place(x, y, (x + y) % 2 == 0 ? CellState.RED : CellState.BLUE);
        return board;
    }
}
