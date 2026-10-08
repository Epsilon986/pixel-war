package org.pixelwar.pixelwar.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoardTests {
    @Test void creationAndValidation() {
        Board board = new Board(3, 2);
        assertEquals(6, board.totalCells());
        assertEquals(CellState.EMPTY, board.get(2, 1));
        assertEquals(6L, board.counts().get(CellState.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> new Board(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new Board(2, -1));
    }
    @Test void placementOverwriteAndReset() {
        Board board = new Board(3, 2);
        assertTrue(board.place(0, 0, CellState.RED));
        assertFalse(board.place(0, 0, CellState.RED));
        assertTrue(board.place(0, 0, CellState.BLUE));
        assertEquals(CellState.BLUE, board.get(0, 0));
        board.place(2, 1, CellState.YELLOW);
        assertEquals(1L, board.counts().get(CellState.BLUE));
        assertEquals(4L, board.counts().get(CellState.EMPTY));
        board.reset();
        assertEquals(6L, board.counts().get(CellState.EMPTY));
    }
    @Test void coordinatesAndColorsAreChecked() {
        Board board = new Board(3, 2);
        assertThrows(IndexOutOfBoundsException.class, () -> board.get(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> board.get(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> board.place(3, 0, CellState.RED));
        assertThrows(IndexOutOfBoundsException.class, () -> board.place(0, -1, CellState.RED));
        assertThrows(IllegalArgumentException.class, () -> board.place(0, 0, CellState.EMPTY));
        assertThrows(NullPointerException.class, () -> board.place(0, 0, null));
    }
    @Test void previewSamplesTheFullBoardAndClampsDimensions() {
        Board board = new Board(4, 6);
        board.place(2, 4, CellState.GREEN);
        CellState[][] preview = board.preview(2, 3);
        assertEquals(3, preview.length);
        assertEquals(2, preview[0].length);
        assertEquals(CellState.GREEN, preview[2][1]);
        assertEquals(6, board.preview(100, 100).length);
        assertThrows(IllegalArgumentException.class, () -> board.preview(0, 2));
    }
}
