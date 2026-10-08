package org.pixelwar.pixelwar.domain;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Simple baseline: a two-dimensional array and one monitor for all access. */
public final class Board {
    private final int width;
    private final int height;
    private final CellState[][] cells;

    public Board(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Board dimensions must be positive");
        this.width = width;
        this.height = height;
        cells = new CellState[height][width];
        reset();
    }

    public int width() { return width; }
    public int height() { return height; }
    public long totalCells() { return (long) width * height; }

    public synchronized CellState get(int x, int y) {
        checkCoordinates(x, y);
        return cells[y][x];
    }

    public synchronized boolean place(int x, int y, CellState color) {
        checkCoordinates(x, y);
        Objects.requireNonNull(color, "color");
        if (color == CellState.EMPTY) throw new IllegalArgumentException("A player color is required");
        boolean changed = cells[y][x] != color;
        cells[y][x] = color;
        return changed;
    }

    public synchronized void reset() {
        for (CellState[] row : cells) Arrays.fill(row, CellState.EMPTY);
    }

    public synchronized Map<CellState, Long> counts() {
        Map<CellState, Long> counts = new EnumMap<>(CellState.class);
        for (CellState state : CellState.values()) counts.put(state, 0L);
        for (CellState[] row : cells) {
            for (CellState state : row) counts.put(state, counts.get(state) + 1);
        }
        return counts;
    }

    public synchronized CellState[][] preview(int requestedWidth, int requestedHeight) {
        if (requestedWidth <= 0 || requestedHeight <= 0) throw new IllegalArgumentException("Preview dimensions must be positive");
        int w = Math.min(width, requestedWidth);
        int h = Math.min(height, requestedHeight);
        CellState[][] result = new CellState[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) result[y][x] = cells[(int) ((long) y * height / h)][(int) ((long) x * width / w)];
        }
        return result;
    }

    private void checkCoordinates(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IndexOutOfBoundsException("Outside board: " + x + ", " + y);
    }
}
