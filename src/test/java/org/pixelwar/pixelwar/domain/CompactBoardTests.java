package org.pixelwar.pixelwar.domain;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompactBoardTests {
    @Test void mappingCornersRectanglesAndInvalidHorizontalCoordinatesDoNotWrap() {
        var board = new Board(7, 3);
        assertEquals(CellState.EMPTY, board.get(6, 2));
        board.place(0, 0, CellState.RED); board.place(6, 0, CellState.BLUE);
        board.place(0, 2, CellState.GREEN); board.place(6, 2, CellState.YELLOW);
        assertEquals(CellState.RED, board.get(0, 0)); assertEquals(CellState.BLUE, board.get(6, 0));
        assertEquals(CellState.GREEN, board.get(0, 2)); assertEquals(CellState.YELLOW, board.get(6, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> board.get(7, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> board.get(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> board.place(7, 0, CellState.RED));
        assertThrows(IndexOutOfBoundsException.class, () -> board.placeForPlayer(-1, 1, CellState.RED));
        assertThrows(IndexOutOfBoundsException.class, () -> board.placeIfEmpty(7, 0, CellState.RED));
        assertEquals(17L, board.counts().get(CellState.EMPTY));
        assertThrows(ArithmeticException.class, () -> new Board(Integer.MAX_VALUE, 2));
        assertThrows(ArithmeticException.class, () -> new Board(65536, 65536));
        assertThrows(IllegalArgumentException.class, () -> new Board(-1, 3));
    }

    @Test void mutationsAndPreviewMatchIndependentTwoDimensionalReference() {
        int width = 13, height = 7;
        var board = new Board(width, height);
        var model = new CellState[height][width];
        for (var row : model) java.util.Arrays.fill(row, CellState.EMPTY);
        var random = new Random(182026);
        for (int step = 0; step < 500; step++) {
            if (step % 127 == 0) {
                board.reset(); for (var row : model) java.util.Arrays.fill(row, CellState.EMPTY);
            }
            int x = random.nextInt(width), y = random.nextInt(height);
            var color = CellState.values()[1 + random.nextInt(4)];
            assertEquals(model[y][x] != color, board.placeForPlayer(x, y, color));
            model[y][x] = color;
            assertFalse(board.place(x, y, color));
            for (var state : CellState.values()) {
                long count = 0;
                for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
                    assertEquals(model[row][col], board.get(col, row));
                    if (model[row][col] == state) count++;
                }
                assertEquals(count, board.counts().get(state));
            }
            var preview = board.preview(5, 3);
            for (int row = 0; row < 3; row++) for (int col = 0; col < 5; col++) {
                assertEquals(model[row * height / 3][col * width / 5], preview[row][col]);
            }
            var empty = board.randomEmptyPosition(random);
            if (empty != null) assertEquals(CellState.EMPTY, model[empty.y()][empty.x()]);
        }
    }
}
