package org.pixelwar.pixelwar.domain;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Coordinates are (x, y), storage is cells[y][x]. Tracks conversion candidates on mutations. */
public final class Board {
    private final CellState[][] cells;
    private final int width;
    private final int height;
    private final EnumMap<CellState, Long> totals = new EnumMap<>(CellState.class);
    private final int[] emptyCells, emptySlots;
    private int emptyCount;
    private final long[] conversionCandidates;
    private int[] candidateIds = new int[16];
    private int candidateCount;

    public Board(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Board dimensions must be positive");
        this.width = width;
        this.height = height;
        cells = new CellState[height][width];
        emptyCells = new int[Math.multiplyExact(width, height)];
        emptySlots = new int[emptyCells.length];
        conversionCandidates = new long[(int) (((long) emptyCells.length + 63) / 64)];
        reset();
    }

    public int width() { return width; }
    public int height() { return height; }
    public long totalCells() { return (long) width * height; }

    public synchronized CellState get(int x, int y) {
        check(x, y);
        return cells[y][x];
    }

    public synchronized boolean place(int x, int y, CellState color) {
        check(x, y);
        Objects.requireNonNull(color, "color");
        if (color == CellState.EMPTY) throw new IllegalArgumentException("Player color must not be EMPTY");
        boolean changed = cells[y][x] != color;
        if (changed) {
            if (cells[y][x] == CellState.EMPTY) {
                int id = y * width + x;
                int slot = emptySlots[id];
                int last = emptyCells[--emptyCount];
                emptyCells[slot] = last;
                emptySlots[last] = slot;
                emptySlots[id] = -1;
            }
            totals.put(cells[y][x], totals.get(cells[y][x]) - 1);
            totals.put(color, totals.get(color) + 1);
        }
        cells[y][x] = color;
        if (changed) {
            markConversionCandidate(x, y);
            markConversionCandidate(x - 1, y);
            markConversionCandidate(x + 1, y);
            markConversionCandidate(x, y - 1);
            markConversionCandidate(x, y + 1);
        }
        return changed;
    }

    private void markConversionCandidate(int x, int y) {
        if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) return;
        int id = y * width + x;
        int word = id >>> 6;
        long bit = 1L << (id & 63);
        if ((conversionCandidates[word] & bit) != 0) return;
        if (candidateCount == candidateIds.length) {
            candidateIds = Arrays.copyOf(candidateIds, (int) Math.min(totalCells(), (long) candidateIds.length * 2));
        }
        conversionCandidates[word] |= bit;
        candidateIds[candidateCount++] = id;
    }

    // Caller holds the board monitor throughout detection and application.
    int conversionCandidateCount() { return candidateCount; }
    int conversionCandidateId(int index) { return candidateIds[index]; }
    void clearConversionCandidates() {
        // Clear only queued bits, with no scan across the rest of the board.
        for (int i = 0; i < candidateCount; i++) {
            int id = candidateIds[i];
            conversionCandidates[id >>> 6] &= ~(1L << (id & 63));
        }
        candidateCount = 0;
    }

    public synchronized void reset() {
        clearConversionCandidates();
        for (CellState[] row : cells) Arrays.fill(row, CellState.EMPTY);
        for (var color : CellState.values()) totals.put(color, 0L);
        totals.put(CellState.EMPTY, totalCells());
        emptyCount = emptyCells.length;
        for (int i = 0; i < emptyCount; i++) { emptyCells[i] = i; emptySlots[i] = i; }
    }

    public synchronized PositionStrategy.Position randomEmptyPosition() {
        if (emptyCount == 0) return null;
        int id = emptyCells[ThreadLocalRandom.current().nextInt(emptyCount)];
        return new PositionStrategy.Position(id % width, id / width);
    }

    public synchronized boolean placeIfEmpty(int x, int y, CellState color) {
        check(x, y);
        Objects.requireNonNull(color, "color");
        if (color == CellState.EMPTY) throw new IllegalArgumentException("Player color must not be EMPTY");
        return cells[y][x] == CellState.EMPTY && place(x, y, color);
    }

    /** Players may replace opponents only once no empty cell remains. */
    public synchronized boolean placeForPlayer(int x, int y, CellState color) {
        check(x, y);
        Objects.requireNonNull(color, "color");
        if (color == CellState.EMPTY) throw new IllegalArgumentException("Player color must not be EMPTY");
        if (cells[y][x] == color) return false;
        if (cells[y][x] != CellState.EMPTY && totals.get(CellState.EMPTY) > 0) return false;
        return place(x, y, color);
    }

    public synchronized Map<CellState, Long> counts() {
        return new EnumMap<>(totals);
    }

    public synchronized CellState[][] preview(int requestedWidth, int requestedHeight) {
        if (requestedWidth <= 0 || requestedHeight <= 0) throw new IllegalArgumentException("Preview dimensions must be positive");
        int w = Math.min(width, requestedWidth);
        int h = Math.min(height, requestedHeight);
        var preview = new CellState[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            preview[y][x] = cells[(int) ((long) y * height / h)][(int) ((long) x * width / w)];
        }
        return preview;
    }

    private void check(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IndexOutOfBoundsException("Outside board: " + x + ", " + y);
    }
}
