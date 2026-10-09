package org.pixelwar.pixelwar.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoardTests {
    @Test void emptyIndexTracksPlacementsConversionsAndReset() {
        var board = new Board(10, 10);
        board.place(0, 0, CellState.RED); // Conversion also removes an empty cell from the index.
        for (int i = 0; i < 99; i++) {
            var position = board.randomEmptyPosition();
            assertNotNull(position);
            assertEquals(CellState.EMPTY, board.get(position.x(), position.y()));
            assertTrue(board.placeForPlayer(position.x(), position.y(), CellState.BLUE));
        }
        assertNull(board.randomEmptyPosition());
        board.place(0, 0, CellState.BLUE);
        assertNull(board.randomEmptyPosition());
        board.reset();
        for (int i = 0; i < 100; i++) {
            var position = board.randomEmptyPosition();
            assertNotNull(position);
            assertTrue(board.placeIfEmpty(position.x(), position.y(), CellState.RED));
        }
        assertNull(board.randomEmptyPosition());
    }
    @Test void playerCanReplaceOpponentOnlyAfterLastEmptyCellIsFilled() {
        var board = new Board(2, 1);
        assertTrue(board.placeForPlayer(0, 0, CellState.BLUE));
        assertFalse(board.placeForPlayer(0, 0, CellState.RED));
        assertTrue(board.placeForPlayer(1, 0, CellState.RED));
        assertFalse(board.placeForPlayer(1, 0, CellState.RED));
        assertTrue(board.placeForPlayer(0, 0, CellState.RED));
        assertEquals(2L, board.counts().get(CellState.RED));
        assertEquals(0L, board.counts().get(CellState.BLUE));
        assertEquals(0L, board.counts().get(CellState.EMPTY));
        board.reset();
        assertTrue(board.placeForPlayer(0, 0, CellState.BLUE));
        assertFalse(board.placeForPlayer(0, 0, CellState.RED));
    }
    @Test void emptyOnlyPlacementPreservesOccupiedCellsAndCounts() {
        var board = new Board(2, 2);
        assertTrue(board.placeIfEmpty(0, 0, CellState.RED));
        assertFalse(board.placeIfEmpty(0, 0, CellState.BLUE));
        assertFalse(board.placeIfEmpty(0, 0, CellState.RED));
        assertEquals(CellState.RED, board.get(0, 0));
        assertEquals(1L, board.counts().get(CellState.RED));
        assertEquals(0L, board.counts().get(CellState.BLUE));
        assertEquals(3L, board.counts().get(CellState.EMPTY));
        board.place(0, 0, CellState.BLUE); // Conversion can still replace a color.
        assertEquals(0L, board.counts().get(CellState.RED));
        assertEquals(1L, board.counts().get(CellState.BLUE));
        var snapshot = board.counts();
        snapshot.put(CellState.BLUE, 99L);
        assertEquals(1L, board.counts().get(CellState.BLUE));
    }
    @Test void creationPlacementOverwriteAndReset() {
        var board = new Board(3, 2);
        assertEquals(6, board.totalCells());
        assertEquals(CellState.EMPTY, board.get(2, 1));
        assertTrue(board.place(0, 0, CellState.RED));
        assertFalse(board.place(0, 0, CellState.RED));
        assertTrue(board.place(0, 0, CellState.BLUE));
        assertEquals(CellState.BLUE, board.get(0, 0));
        assertEquals(1L, board.counts().get(CellState.BLUE));
        board.reset();
        assertEquals(6L, board.counts().get(CellState.EMPTY));
    }
    @Test void boundariesAndValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Board(0, 1));
        var board = new Board(2, 2);
        assertThrows(IndexOutOfBoundsException.class, () -> board.get(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> board.get(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> board.place(2, 0, CellState.RED));
        assertThrows(IndexOutOfBoundsException.class, () -> board.place(0, -1, CellState.RED));
        assertThrows(IllegalArgumentException.class, () -> board.place(0, 0, CellState.EMPTY));
        assertThrows(NullPointerException.class, () -> board.place(0, 0, null));
    }
    @Test void previewSamplesAndClamps() {
        var board = new Board(4, 6);
        board.place(2, 4, CellState.GREEN);
        assertEquals(CellState.GREEN, board.preview(2, 3)[2][1]);
        assertEquals(6, board.preview(100, 100).length);
        assertEquals(4, board.preview(100, 100)[0].length);
        assertThrows(IllegalArgumentException.class, () -> board.preview(0, 1));
    }
}
