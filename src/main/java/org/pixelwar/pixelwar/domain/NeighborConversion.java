package org.pixelwar.pixelwar.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One global pass. Detection sees the pre-conversion board; application is separate. */
public final class NeighborConversion {
    public record Change(int x, int y, CellState color) {}

    public List<Change> detect(Board board) {
        synchronized (board) {
            var changes = new ArrayList<Change>();
            for (int y = 1; y < board.height() - 1; y++) {
                for (int x = 1; x < board.width() - 1; x++) {
                    var color = board.get(x, y - 1);
                    if (color != CellState.EMPTY && board.get(x, y) != color
                            && board.get(x, y + 1) == color && board.get(x - 1, y) == color && board.get(x + 1, y) == color) {
                        changes.add(new Change(x, y, color));
                    }
                }
            }
            return changes;
        }
    }

    public Map<CellState, Long> apply(Board board, List<Change> changes) {
        synchronized (board) {
            var received = new EnumMap<CellState, Long>(CellState.class);
            for (var color : CellState.values()) received.put(color, 0L);
            for (var change : changes) {
                if (board.place(change.x(), change.y(), change.color())) received.put(change.color(), received.get(change.color()) + 1);
            }
            return received;
        }
    }

    public Map<CellState, Long> convert(Board board) {
        synchronized (board) { return apply(board, detect(board)); }
    }
}
