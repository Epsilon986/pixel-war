package org.pixelwar.pixelwar.domain;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The engine has no dependency on HTTP or Spring. All state is guarded by this monitor. */
public final class Simulation implements AutoCloseable {
    public enum State { STOPPED, RUNNING, PAUSED }
    public record Player(int id, String name, CellState color, long intervalNs) {}
    public record Configuration(int width, int height, long intervalNs, int previewWidth, int previewHeight) {}
    public record Status(State state, long elapsedMs, Configuration configuration, long attempts, long modifications) {}
    public record Score(Player player, long cells, double percentage) {}
    public record PlayerMetrics(Player player, long attempts, long modifications) {}
    public record Latency(double averageNanos, long minimumNanos, long maximumNanos) {}
    public record Jvm(long heapUsedBytes, long heapMaxBytes, int activeThreads, int availableProcessors, Double processCpuLoad) {}
    public record Metrics(Status simulation, double attemptsPerSecond, double modificationsPerSecond,
                          Latency latency, List<PlayerMetrics> players, Jvm jvm, long totalCells, Map<CellState, Long> cellsByColor) {}
    public record Preview(int width, int height, CellState[][] cells) {}

    private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
    private final Board board;
    private final Configuration configuration;
    private final List<Player> players;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private final long[] attemptsByPlayer = new long[4];
    private final long[] modificationsByPlayer = new long[4];
    private State state = State.STOPPED;
    private long accumulatedNanos, runningSince, attempts, modifications, latencyTotal, latencyMin = Long.MAX_VALUE, latencyMax;
    private boolean closed;
    private long generation;

    public Simulation(Board board, long intervalNs, int previewWidth, int previewHeight) {
        if (intervalNs <= 0 || previewWidth <= 0 || previewHeight <= 0) throw new IllegalArgumentException("Interval and preview dimensions must be positive");
        this.board = board;
        configuration = new Configuration(board.width(), board.height(), intervalNs, previewWidth, previewHeight);
        players = List.of(new Player(1, "Player 1", CellState.RED, intervalNs), new Player(2, "Player 2", CellState.BLUE, intervalNs),
                new Player(3, "Player 3", CellState.GREEN, intervalNs), new Player(4, "Player 4", CellState.YELLOW, intervalNs));
        LOG.info("Board configured: {} x {}, interval {} ns", board.width(), board.height(), intervalNs);
    }

    public synchronized Status start() {
        if (closed) throw new IllegalStateException("Simulation is closed");
        if (state == State.PAUSED) throw new IllegalStateException("Use resume for a paused simulation");
        if (state == State.RUNNING) return status();
        runningSince = System.nanoTime();
        state = State.RUNNING;
        schedulePlayers();
        LOG.info("Simulation started");
        return status();
    }

    public synchronized Status pause() {
        if (state == State.PAUSED) return status();
        if (state != State.RUNNING) throw new IllegalStateException("Only a running simulation can pause");
        freeze(State.PAUSED);
        cancelPlayers();
        LOG.info("Simulation paused");
        return status();
    }

    public synchronized Status resume() {
        if (state == State.RUNNING) return status();
        if (state != State.PAUSED) throw new IllegalStateException("Only a paused simulation can resume");
        runningSince = System.nanoTime();
        state = State.RUNNING;
        schedulePlayers();
        LOG.info("Simulation resumed");
        return status();
    }

    public synchronized Status stop() {
        freeze(State.STOPPED);
        cancelPlayers();
        LOG.info("Simulation stopped");
        return status();
    }

    private void schedulePlayers() {
        long currentGeneration = ++generation;
        for (Player player : players) tasks.add(executor.scheduleAtFixedRate(() -> play(player, currentGeneration), 0, player.intervalNs(), TimeUnit.NANOSECONDS));
    }

    private void cancelPlayers() {
        generation++;
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
    }

    public synchronized Status reset() {
        stop();
        board.reset();
        accumulatedNanos = attempts = modifications = latencyTotal = latencyMax = 0;
        latencyMin = Long.MAX_VALUE;
        Arrays.fill(attemptsByPlayer, 0);
        Arrays.fill(modificationsByPlayer, 0);
        LOG.info("Simulation reset");
        return status();
    }

    private void freeze(State next) {
        if (state == State.RUNNING) accumulatedNanos += System.nanoTime() - runningSince;
        state = next;
    }

    private void play(Player player, long expectedGeneration) {
        long before = System.nanoTime();
        synchronized (this) {
            if (state != State.RUNNING || generation != expectedGeneration || Thread.currentThread().isInterrupted()) return;
            try {
                var random = ThreadLocalRandom.current();
                boolean changed = board.place(random.nextInt(board.width()), random.nextInt(board.height()), player.color());
                attempts++;
                attemptsByPlayer[player.id() - 1]++;
                if (changed) { modifications++; modificationsByPlayer[player.id() - 1]++; }
                long duration = System.nanoTime() - before;
                latencyTotal += duration;
                latencyMin = Math.min(latencyMin, duration);
                latencyMax = Math.max(latencyMax, duration);
            } catch (RuntimeException exception) {
                LOG.error("Unexpected player action failure", exception);
                stop();
            }
        }
    }

    private long elapsedNanos() { return accumulatedNanos + (state == State.RUNNING ? System.nanoTime() - runningSince : 0); }
    public synchronized Status status() { return new Status(state, elapsedNanos() / 1_000_000, configuration, attempts, modifications); }
    public synchronized List<Score> scores() {
        var counts = board.counts();
        return players.stream().map(player -> new Score(player, counts.get(player.color()), counts.get(player.color()) * 100.0 / board.totalCells())).toList();
    }
    public synchronized Preview preview() {
        var cells = board.preview(configuration.previewWidth(), configuration.previewHeight());
        return new Preview(cells[0].length, cells.length, cells);
    }
    public synchronized Metrics metrics() {
        double seconds = elapsedNanos() / 1_000_000_000.0;
        Status snapshot = new Status(state, (long) (seconds * 1000), configuration, attempts, modifications);
        var perPlayer = players.stream().map(p -> new PlayerMetrics(p, attemptsByPlayer[p.id() - 1], modificationsByPlayer[p.id() - 1])).toList();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = ManagementFactory.getOperatingSystemMXBean();
        Double cpu = null;
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            double load = extended.getProcessCpuLoad();
            if (load >= 0) cpu = load;
        }
        var jvm = new Jvm(heap.getUsed(), heap.getMax(), ManagementFactory.getThreadMXBean().getThreadCount(), os.getAvailableProcessors(), cpu);
        return new Metrics(snapshot, seconds == 0 ? 0 : attempts / seconds, seconds == 0 ? 0 : modifications / seconds,
                new Latency(attempts == 0 ? 0 : (double) latencyTotal / attempts, attempts == 0 ? 0 : latencyMin, latencyMax),
                perPlayer, jvm, board.totalCells(), board.counts());
    }

    @Override public synchronized void close() { stop(); closed = true; executor.shutdownNow(); }
}
