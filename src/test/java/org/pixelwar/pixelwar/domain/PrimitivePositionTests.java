package org.pixelwar.pixelwar.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimitivePositionTests {
    // Retained O3 baseline oracle: ObjectFrontierReference is copied from ea462e1.
    static final class TraceRandom extends Random {
        final List<String> calls = new ArrayList<>();
        final boolean duplicates;
        TraceRandom(long seed, boolean duplicates) { super(seed); this.duplicates = duplicates; }
        @Override public int nextInt(int bound) {
            int value = duplicates ? 0 : super.nextInt(bound);
            calls.add("int(" + bound + ")=" + value);
            return value;
        }
        @Override public double nextDouble() {
            double value = super.nextDouble(); calls.add("double=" + value); return value;
        }
    }

    private Board board(int state) {
        var board = new Board(7, 5);
        if (state == 1) { board.place(0, 0, CellState.RED); board.place(6, 4, CellState.BLUE); }
        if (state >= 2) {
            for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++)
                if (state != 4 || x != 6 || y != 4)
                    board.place(x, y, state == 2 ? CellState.BLUE : CellState.values()[1 + (x + 2*y) % 4]);
        }
        return board;
    }

    @Test void selectionAndEveryRandomCallMatchObjectReferenceAcrossMutationsAndReset() {
        for (double exploration : new double[]{0, .1, 1}) for (int sample : new int[]{1, 10})
            for (int state = 0; state <= 4; state++) for (boolean duplicates : new boolean[]{false, true}) {
                var a = board(state); var b = board(state);
                var ar = new TraceRandom(33, duplicates); var br = new TraceRandom(33, duplicates);
                var actual = new FrontierPlacementStrategy(ar, exploration, sample, 8, 3);
                var reference = new ObjectFrontierReference(br, exploration, sample, 8, 3);
                for (int action = 0; action < 24; action++) {
                    var color = CellState.values()[1 + action % 4];
                    var player = new Simulation.Player(action % 4, color.name(), color, 1);
                    actual.beginAction(a, player); reference.beginAction(b, player);
                    assertEquals(reference.priorityTarget(b, color), actual.priorityTarget(a, color));
                    for (int pose = 0; pose < 3; pose++) {
                        var counts = a.counts(); var maintenance = a.frontierMetrics();
                        var expected = reference.choose(b, player); var selected = actual.choose(a, player);
                        assertEquals(expected, selected, "state=" + state + ", sample=" + sample);
                        assertEquals(br.calls, ar.calls, "draw type, bound, value and order");
                        assertEquals(counts, a.counts()); assertEquals(maintenance, a.frontierMetrics());
                        assertEquals(b.placeForPlayer(expected.x(), expected.y(), color), a.placeForPlayer(selected.x(), selected.y(), color));
                    }
                    assertEquals(b.counts(), a.counts());
                    for (var c : CellState.values()) if (c != CellState.EMPTY) assertEquals(b.frontierSize(c), a.frontierSize(c));
                    if (action == 11) { a.reset(); b.reset(); actual.reset(a); reference.reset(b); }
                }
                for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) assertEquals(b.get(x, y), a.get(x, y));
            }
    }

    @Test void primitiveScoresAndPositionAdaptersMatchReferenceIncludingConversionAndWeakness() {
        var board = board(3);
        board.place(1, 2, CellState.RED); board.place(3, 2, CellState.RED); board.place(2, 3, CellState.RED);
        for (int bonus : new int[]{0, 8}) {
            var actual = new FrontierPlacementStrategy(new Random(11), 0, 10, bonus, 20);
            var reference = new ObjectFrontierReference(new Random(11), 0, 10, bonus, 20);
            for (var ally : CellState.values()) if (ally != CellState.EMPTY) {
                var player = new Simulation.Player(ally.ordinal(), ally.name(), ally, 1);
                actual.beginAction(board, player); reference.beginAction(board, player);
                var counts = board.counts(); var maintenance = board.frontierMetrics();
                for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) {
                    var p = new PlacementStrategy.Position(x, y);
                    assertEquals(reference.score(board, p, ally), actual.score(board, x, y, ally));
                    assertEquals(reference.conquestScore(board, p, ally), actual.conquestScore(board, x, y, ally));
                    assertEquals(actual.score(board, p, ally), actual.score(board, x, y, ally));
                    assertEquals(actual.conquestScore(board, p, ally), actual.conquestScore(board, x, y, ally));
                }
                assertEquals(counts, board.counts()); assertEquals(maintenance, board.frontierMetrics());
            }
        }
    }

    @Test void primitiveFrontierAdapterRetainsSentinelValidationAndRandomSequence() {
        var board = new Board(7, 5);
        var ar = new TraceRandom(22, false); var br = new TraceRandom(22, false);
        assertEquals(-1, board.randomFrontierId(CellState.RED, ar));
        assertNull(board.randomFrontierPosition(CellState.RED, br));
        assertTrue(ar.calls.isEmpty()); assertTrue(br.calls.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> board.randomFrontierId(null, ar));
        assertThrows(IllegalArgumentException.class, () -> board.randomFrontierId(CellState.EMPTY, ar));
        board.place(0, 0, CellState.RED); board.place(6, 4, CellState.RED);
        for (int i = 0; i < 100; i++) {
            int id = board.randomFrontierId(CellState.RED, ar);
            assertEquals(new PlacementStrategy.Position(id % 7, id / 7), board.randomFrontierPosition(CellState.RED, br));
            assertTrue(board.isFrontier(id % 7, id / 7, CellState.RED));
        }
        assertEquals(br.calls, ar.calls);
        board.reset(); assertEquals(-1, board.randomFrontierId(CellState.RED, ar));
    }
}
