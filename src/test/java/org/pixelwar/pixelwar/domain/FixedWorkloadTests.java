package org.pixelwar.pixelwar.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FixedWorkloadTests {
    private record Fixture(Simulation simulation, List<Runnable> tasks, AtomicLong clock) implements AutoCloseable {
        public void close() { simulation.close(); }
    }
    private Fixture fixture(boolean stock) {
        var tasks = new ArrayList<Runnable>();
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(call -> { tasks.add(call.getArgument(0)); return mock(ScheduledFuture.class); });
        var config = new Simulation.Configuration(12, 12, 10, 12, 12, new Stock.Configuration(10, 100, 10, 1000, stock), 3, true);
        var clock = new AtomicLong();
        return new Fixture(new Simulation(new Board(12, 12), config, new RandomPlacementStrategy(new java.util.Random(1)), clock::get, scheduler), tasks, clock);
    }
    @Test void trialStopsAtExactNMidRoundAndSeedReproducesBoardAndCounters() {
        try (var fixture = fixture(false)) {
            var simulation = fixture.simulation();
            simulation.startTrial(17, 42);
            for (int i = 0; i < 8; i++) { fixture.clock().addAndGet(100_000); fixture.tasks().getFirst().run(); }
            var first = simulation.performance();
            assertTrue(first.completed());
            assertEquals(17, first.measurements().actions());
            assertEquals(51, first.measurements().attempts());
            assertEquals(Simulation.State.PAUSED, simulation.status().state());
            assertThrows(IllegalStateException.class, simulation::resume);
            var preview = simulation.preview();
            var scores = simulation.scores();
            simulation.startTrial(17, 42);
            for (int i = 0; i < 8; i++) { fixture.clock().addAndGet(100_000); fixture.tasks().get(1).run(); }
            assertEquals(scores, simulation.scores());
            assertArrayEquals(preview.cells(), simulation.preview().cells());
            assertEquals(first.measurements().actions(), simulation.performance().measurements().actions());
            fixture.tasks().getFirst().run(); // Old generation must not alter the new trial.
            assertEquals(17, simulation.performance().measurements().actions());
            simulation.reset();
            assertNull(simulation.performance().targetActions());
            assertEquals(0, simulation.performance().measurements().actions());
            assertFalse(simulation.performance().completed());
        }
    }
    @Test void pauseDoesNotCompleteTrialAndExcludesPausedTime() {
        try (var fixture = fixture(false)) {
            fixture.simulation().startTrial(100, 42);
            fixture.tasks().getFirst().run();
            fixture.clock().set(500);
            fixture.simulation().pause();
            var before = fixture.simulation().performance();
            fixture.clock().set(1_000_000);
            assertEquals(before, fixture.simulation().performance());
            fixture.simulation().resume();
            fixture.clock().set(1_000_500);
            fixture.tasks().get(1).run();
            fixture.simulation().pause();
            assertEquals(1000, fixture.simulation().performance().measurements().elapsedNanos());
            assertFalse(fixture.simulation().performance().completed());
        }
    }
    @Test void invalidTrialDoesNotResetExistingSimulationAndStockModeIsExplicitlyRejected() {
        try (var fixture = fixture(false)) {
            fixture.simulation().start(); fixture.tasks().getFirst().run();
            var before = fixture.simulation().status();
            assertThrows(IllegalArgumentException.class, () -> fixture.simulation().startTrial(0, 42));
            assertThrows(IllegalArgumentException.class, () -> fixture.simulation().startTrial(1_000_000_001L, 42));
            assertEquals(before, fixture.simulation().status());
        }
        try (var fixture = fixture(true)) {
            assertThrows(IllegalStateException.class, () -> fixture.simulation().startTrial(10, 42));
            assertEquals(Simulation.State.STOPPED, fixture.simulation().status().state());
        }
    }
}
