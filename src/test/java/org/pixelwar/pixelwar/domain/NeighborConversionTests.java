package org.pixelwar.pixelwar.domain;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeighborConversionTests {
    private final NeighborConversion conversion = new NeighborConversion();

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
