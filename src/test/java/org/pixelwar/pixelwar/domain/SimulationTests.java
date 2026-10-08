package org.pixelwar.pixelwar.domain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTests {
    @Test void scoresScanTheBoard() {
        Board board = new Board(2, 2);
        board.place(0, 0, CellState.RED);
        board.place(1, 0, CellState.RED);
        board.place(0, 1, CellState.BLUE);
        try (Simulation simulation = new Simulation(board, 10_000_000, 2, 2)) {
            var scores = simulation.scores();
            assertEquals(4, scores.size());
            assertEquals(2, scores.getFirst().cells());
            assertEquals(50, scores.getFirst().percentage());
            assertEquals(25, scores.get(1).percentage());
            assertEquals(1L, simulation.metrics().cellsByColor().get(CellState.EMPTY));
            assertEquals(0, simulation.metrics().latency().minimumNanos());
        }
    }
    @Test void lifecycleFreezesActionsAndResetsAllCounters() throws Exception {
        try (Simulation simulation = new Simulation(new Board(1, 1), 2_000_000, 1, 1)) {
            assertEquals(Simulation.State.STOPPED, simulation.status().state());
            assertThrows(IllegalStateException.class, simulation::pause);
            assertThrows(IllegalStateException.class, simulation::resume);
            simulation.start();
            simulation.start();
            awaitAttempts(simulation, 20);
            simulation.pause();
            var paused = simulation.status();
            Thread.sleep(30);
            assertEquals(paused, simulation.status());
            assertThrows(IllegalStateException.class, simulation::start);
            var metrics = simulation.metrics();
            assertEquals(paused.attempts(), metrics.players().stream().mapToLong(Simulation.PlayerMetrics::attempts).sum());
            assertEquals(paused.modifications(), metrics.players().stream().mapToLong(Simulation.PlayerMetrics::modifications).sum());
            assertTrue(paused.modifications() <= paused.attempts());
            assertTrue(metrics.players().stream().allMatch(p -> p.attempts() > 0));
            assertTrue(metrics.latency().minimumNanos() <= metrics.latency().averageNanos());
            assertTrue(metrics.latency().averageNanos() <= metrics.latency().maximumNanos());
            simulation.resume();
            awaitAttempts(simulation, paused.attempts() + 4);
            simulation.stop();
            var stopped = simulation.status();
            Thread.sleep(30);
            assertEquals(stopped, simulation.status());
            simulation.start();
            awaitAttempts(simulation, stopped.attempts() + 4);
            simulation.reset();
            Thread.sleep(30);
            assertEquals(Simulation.State.STOPPED, simulation.status().state());
            assertEquals(0, simulation.status().attempts());
            assertEquals(0, simulation.status().modifications());
            assertEquals(0, simulation.status().elapsedMs());
            assertEquals(1L, simulation.metrics().cellsByColor().get(CellState.EMPTY));
        }
    }
    @Test void nanosecondIntervalsGenerateLoadAndPauseCleanly() throws Exception {
        try (Simulation simulation = new Simulation(new Board(20, 20), 1_000, 10, 10)) {
            assertEquals(1_000, simulation.status().configuration().intervalNs());
            simulation.start();
            awaitAttempts(simulation, 10_000);
            simulation.pause();
            var paused = simulation.status();
            Thread.sleep(20);
            assertEquals(paused, simulation.status());
            assertTrue(simulation.metrics().players().stream().allMatch(p -> p.player().intervalNs() == 1_000));
            simulation.resume();
            awaitAttempts(simulation, paused.attempts() + 1_000);
            simulation.reset();
            assertEquals(0, simulation.status().attempts());
        }
    }

    private void awaitAttempts(Simulation simulation, long target) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (simulation.status().attempts() < target) Thread.sleep(2);
        });
    }
}
