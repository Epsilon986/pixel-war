package org.pixelwar.pixelwar.domain;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimulationTests {
    @Test void frontierPosesUseUpdatedFrontiersWithinOneActionAndDebitOnlyChanges() {
        var board = new Board(15, 15);
        board.place(7, 7, CellState.RED);
        try (var fixture = fixture(board, 7, 4, false, new FrontierPlacementStrategy(new java.util.Random(123), 0, 10))) {
            fixture.simulation().start();
            fixture.action(0);
            assertEquals(4, fixture.simulation().status().attempts());
            assertEquals(4, fixture.simulation().status().modifications());
            assertEquals(3, fixture.simulation().status().players().getFirst().stock());
            assertEquals(5L, board.counts().get(CellState.RED));
            assertEquals(5, board.frontierMetrics().updates());
            fixture.simulation().reset();
            assertEquals(0, board.frontierSize(CellState.RED));
            assertEquals(0, board.frontierMetrics().updates());
        }
    }

    @Test void emptyStockSkipsFrontierSelectionAndConversions() {
        var strategy = mock(PlacementStrategy.class);
        try (var fixture = fixture(new Board(5, 5), 0, 4, true, strategy)) {
            fixture.simulation().start(); fixture.action(0);
            verifyNoInteractions(strategy);
            assertEquals(1, fixture.simulation().metrics().actionsWithoutStock());
            assertEquals(0, fixture.simulation().metrics().conversionPasses());
        }
    }
    @Test void scheduledRoundsRotateFirstPlayerAndGuaranteeEmptyCellPlacements() {
        var order = new ArrayList<Integer>();
        var random = PositionStrategy.random();
        try (var fixture = fixture(new Board(4, 4), 10, 1, false, (b, p) -> {
            order.add(p.id());
            return random.choose(b, p);
        })) {
            fixture.simulation().start();
            assertEquals(1, fixture.tasks().size());
            for (int i = 0; i < 4; i++) fixture.tasks().getFirst().run();
            assertEquals(List.of(1, 2, 3, 4, 2, 3, 4, 1, 3, 4, 1, 2, 4, 1, 2, 3), order);
            var metrics = fixture.simulation().metrics();
            assertEquals(16, metrics.simulation().attempts());
            assertEquals(16, metrics.simulation().modifications());
            assertEquals(0L, metrics.cellsByColor().get(CellState.EMPTY));
            for (var player : metrics.players()) {
                assertEquals(4, player.actions());
                assertEquals(4, player.modifications());
                assertEquals(BigInteger.valueOf(4), player.pixelsConsumed());
            }
            for (var score : fixture.simulation().scores()) assertEquals(4, score.cells());
        }
    }
    @Test void fullBoardAllowsOpponentReplacementAndEliminatesLoser() {
        var board = new Board(2, 1);
        board.place(0, 0, CellState.BLUE);
        board.place(1, 0, CellState.RED);
        try (var fixture = fixture(board, 7, 1, false, (b, p) -> new PositionStrategy.Position(0, 0))) {
            var simulation = fixture.simulation();
            simulation.start(); fixture.action(0);
            assertEquals(CellState.RED, board.get(0, 0));
            assertTrue(simulation.status().players().get(1).eliminated());
            assertEquals(6, simulation.status().players().getFirst().stock());
            fixture.action(0); // Own color is rejected even on a full board.
            fixture.action(1); // Eliminated player cannot reclaim a cell.
            assertEquals(2, simulation.status().attempts());
            assertEquals(1, simulation.status().modifications());
            assertEquals(BigInteger.ONE, simulation.metrics().pixelsConsumed());
            assertEquals(6, simulation.status().players().getFirst().stock());
        }
    }
    record Fixture(Simulation simulation, AtomicLong clock, List<Runnable> tasks) implements AutoCloseable {
        void action(int player) { simulation.play(player, 1); }
        @Override public void close() { simulation.close(); }
    }
    Fixture fixture(Board board, int initial, int max, boolean convert, PlacementStrategy strategy) {
        return fixture(board, initial, max, convert, strategy, true);
    }
    Fixture fixture(Board board, int initial, int max, boolean convert, PlacementStrategy strategy, boolean stockEnabled) {
        var tasks = new ArrayList<Runnable>();
        var clock = new AtomicLong();
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(call -> { tasks.add(call.getArgument(0)); return mock(ScheduledFuture.class); });
        var config = new Simulation.Configuration(board.width(), board.height(), 1000, 10, 10,
                new Stock.Configuration(initial, 100, 5, 10_000_000, stockEnabled), max, convert);
        return new Fixture(new Simulation(board, config, strategy, clock::get, scheduler), clock, tasks);
    }
    @Test void disabledStockAllowsEveryActionWithEmptyStockAndNoRecharge() {
        try (var fixture = fixture(new Board(3, 3), 0, 101, false,
                (b, p) -> new PositionStrategy.Position(0, 0), false)) {
            fixture.simulation().start();
            fixture.action(0);
            fixture.clock().set(20_000_000);
            fixture.action(0);
            var metrics = fixture.simulation().metrics();
            assertEquals(202, metrics.simulation().attempts());
            assertEquals(0, metrics.actionsWithoutStock());
            assertEquals(BigInteger.ZERO, metrics.pixelsConsumed());
            assertEquals(BigInteger.ZERO, metrics.pixelsRefilled());
            assertEquals(BigInteger.ZERO, metrics.pixelsDiscardedAtCapacity());
            assertEquals(0, metrics.simulation().players().getFirst().stock());
            fixture.simulation().reset();
            assertFalse(fixture.simulation().status().configuration().stock().enabled());
        }
    }
    @Test void occupiedCellsRejectPosesWithoutConsumingStock() {
        var board = new Board(3, 3);
        board.place(0, 0, CellState.BLUE);
        try (var fixture = fixture(board, 7, 4, false, (b, p) -> new PositionStrategy.Position(0, 0))) {
            var simulation = fixture.simulation();
            simulation.start(); fixture.action(0); fixture.action(0); fixture.action(0);
            var metrics = simulation.metrics();
            assertEquals(12, metrics.simulation().attempts());
            assertEquals(0, metrics.simulation().modifications());
            assertEquals(3, metrics.actions());
            assertEquals(0, metrics.actionsWithoutStock());
            assertEquals(BigInteger.ZERO, metrics.pixelsConsumed());
            assertEquals(7, simulation.status().players().getFirst().stock());
            assertEquals(0, metrics.conversionPasses());
            assertEquals(0, simulation.scores().getFirst().cells());
            assertEquals(CellState.BLUE, board.get(0, 0));
        }
    }
    @Test void allPosesPrecedeConversionAndConversionDoesNotConsumeStock() {
        var board = new Board(3, 3);
        var positions = List.of(new PositionStrategy.Position(1, 0), new PositionStrategy.Position(1, 2),
                new PositionStrategy.Position(0, 1), new PositionStrategy.Position(2, 1));
        var index = new AtomicInteger();
        try (var fixture = fixture(board, 7, 4, true, (b, p) -> positions.get(index.getAndIncrement()))) {
            fixture.simulation().start(); fixture.action(0);
            var metrics = fixture.simulation().metrics();
            assertEquals(4, metrics.simulation().attempts());
            assertEquals(4, metrics.simulation().modifications());
            assertEquals(1, metrics.conversionPasses());
            assertEquals(1, metrics.conversions());
            assertEquals(5, metrics.boardChanges());
            assertEquals(3, fixture.simulation().status().players().getFirst().stock());
            assertEquals(5, fixture.simulation().scores().getFirst().cells());
            assertEquals(CellState.RED, fixture.simulation().preview().cells()[1][1]);
        }
    }
    @Test void convertsAfterNoOpPoseAndAttributesToBeneficiary() {
        var board = new Board(3, 3);
        NeighborConversionTests.surround(board, CellState.RED);
        board.place(0, 0, CellState.BLUE);
        try (var fixture = fixture(board, 1, 1, true, (b, p) -> new PositionStrategy.Position(0, 0))) {
            fixture.simulation().start(); fixture.action(1); fixture.action(1);
            var metrics = fixture.simulation().metrics();
            assertEquals(0, metrics.simulation().modifications());
            assertEquals(2, metrics.simulation().attempts());
            assertEquals(2, metrics.conversionPasses());
            assertEquals(1, metrics.players().getFirst().conversionsReceived());
            assertEquals(0, metrics.players().get(1).conversionsReceived());
            assertEquals(0, metrics.actionsWithoutStock());
        }
    }
    @Test void losingLastPixelEliminatesPlayerUntilReset() {
        var board = new Board(3, 3);
        NeighborConversionTests.surround(board, CellState.RED);
        board.place(1, 1, CellState.BLUE);
        try (var fixture = fixture(board, 7, 1, true, (b, p) -> new PositionStrategy.Position(0, 0))) {
            var simulation = fixture.simulation();
            simulation.start();
            assertFalse(simulation.status().players().get(1).eliminated());
            fixture.action(0);
            assertTrue(simulation.status().players().get(1).eliminated());
            var before = simulation.metrics();
            fixture.action(1);
            assertEquals(before.actions(), simulation.metrics().actions());
            assertEquals(before.simulation().attempts(), simulation.status().attempts());
            assertEquals(7, simulation.status().players().get(1).stock());
            simulation.pause(); simulation.resume();
            simulation.play(1, 3);
            assertEquals(before.simulation().attempts(), simulation.status().attempts());
            simulation.reset(); simulation.start();
            assertFalse(simulation.status().players().get(1).eliminated());
            simulation.play(1, 5);
            assertEquals(CellState.BLUE, board.get(0, 0));
            assertEquals(6, simulation.status().players().get(1).stock());
        }
    }
    @Test void disabledConversionLeavesConvertibleCellUnchanged() {
        var board = new Board(3, 3);
        NeighborConversionTests.surround(board, CellState.RED);
        try (var fixture = fixture(board, 1, 1, false, (b, p) -> new PositionStrategy.Position(0, 0))) {
            fixture.simulation().start(); fixture.action(1);
            assertEquals(CellState.EMPTY, board.get(1, 1));
            assertEquals(0, fixture.simulation().metrics().conversionPasses());
        }
    }
    @Test void lifecycleFreezesRechargeAndRejectsOldTasksAfterRestart() {
        try (var fixture = fixture(new Board(3, 3), 0, 10, true, (b, p) -> new PositionStrategy.Position(0, 0))) {
            var simulation = fixture.simulation();
            assertThrows(IllegalStateException.class, simulation::pause);
            assertThrows(IllegalStateException.class, simulation::resume);
            simulation.start(); simulation.start();
            assertEquals(1, fixture.tasks().size());
            fixture.clock().set(9_000_000); fixture.action(0);
            simulation.pause();
            var paused = simulation.status();
            fixture.clock().set(99_000_000); fixture.action(0);
            assertEquals(paused, simulation.status());
            assertThrows(IllegalStateException.class, simulation::start);
            simulation.resume(); simulation.resume();
            fixture.clock().set(100_000_000);
            simulation.play(0, 3);
            assertEquals(5, simulation.status().attempts());
            assertEquals(BigInteger.valueOf(5), simulation.metrics().pixelsRefilled());
            simulation.stop();
            var stopped = simulation.status();
            fixture.clock().set(500_000_000); fixture.tasks().get(1).run();
            assertEquals(stopped, simulation.status());
            simulation.start();
            fixture.tasks().get(1).run(); // stale task must not become active after restart
            assertEquals(stopped.attempts(), simulation.status().attempts());
            simulation.reset();
            fixture.tasks().get(2).run();
            var metrics = simulation.metrics();
            assertEquals(Simulation.State.STOPPED, metrics.simulation().state());
            assertEquals(0, metrics.actions());
            assertEquals(0, metrics.simulation().elapsedMs());
            assertEquals(0, metrics.conversions());
            assertEquals(0, metrics.conversionDurationNanos().totalNanos());
            assertEquals(BigInteger.ZERO, metrics.pixelsRefilled());
            assertEquals(9L, metrics.cellsByColor().get(CellState.EMPTY));
        }
    }
    @Test void unexpectedErrorStopsSimulationButRetainsCompletedPoses() {
        var calls = new AtomicInteger();
        try (var fixture = fixture(new Board(3, 3), 7, 4, true, (b, p) -> {
            if (calls.getAndIncrement() == 1) throw new IllegalArgumentException("test failure");
            return new PositionStrategy.Position(0, 0);
        })) {
            fixture.simulation().start(); fixture.action(0);
            assertEquals(Simulation.State.STOPPED, fixture.simulation().status().state());
            assertEquals(1, fixture.simulation().status().attempts());
            assertEquals(6, fixture.simulation().status().players().getFirst().stock());
        }
    }
    @Test void realNanosecondSchedulerPausesResumesAndResetsWithoutLateWrites() throws Exception {
        var config = new Simulation.Configuration(20, 20, 1_000, 10, 10,
                new Stock.Configuration(100, 1000, 100, 1_000), 10, true);
        try (var simulation = new Simulation(config)) {
            simulation.start();
            awaitAttempts(simulation, 1000);
            simulation.pause();
            var paused = simulation.status();
            var conversions = simulation.metrics().conversions();
            Thread.sleep(20);
            assertEquals(paused, simulation.status());
            assertEquals(conversions, simulation.metrics().conversions());
            simulation.resume();
            awaitAttempts(simulation, paused.attempts() + 1000);
            simulation.reset();
            Thread.sleep(20);
            assertEquals(0, simulation.status().attempts());
            assertTrue(simulation.status().players().stream().allMatch(p -> p.stock() == 100));
        }
    }
    private void awaitAttempts(Simulation simulation, long target) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (simulation.status().attempts() < target) Thread.sleep(2);
        });
    }
}
