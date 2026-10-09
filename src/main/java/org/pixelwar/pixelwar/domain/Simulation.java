package org.pixelwar.pixelwar.domain;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A simple global monitor guards actions, lifecycle and coherent snapshots. No HTTP dependency. */
public final class Simulation implements AutoCloseable {
    public enum State { STOPPED, RUNNING, PAUSED }
    public record Player(int id, String name, CellState color, long intervalNs) {}
    public record Configuration(int width, int height, long intervalNs, int previewWidth, int previewHeight,
                                Stock.Configuration stock, int maxPixelsPerAction, boolean conversionEnabled,
                                String strategy, double explorationProbability, int frontierSampleSize) {
        public Configuration(int width, int height, long intervalNs, int previewWidth, int previewHeight,
                             Stock.Configuration stock, int maxPixelsPerAction, boolean conversionEnabled) {
            this(width, height, intervalNs, previewWidth, previewHeight, stock, maxPixelsPerAction,
                    conversionEnabled, "frontier", 0.10, 10);
        }
        public Configuration {
            PlacementStrategy.validate(strategy, explorationProbability, frontierSampleSize);
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("pixelwar.board dimensions must be positive");
            if (intervalNs <= 0) throw new IllegalArgumentException("pixelwar.players.interval-ns must be positive");
            if (previewWidth <= 0 || previewHeight <= 0) throw new IllegalArgumentException("pixelwar.preview dimensions must be positive");
            if (stock == null) throw new IllegalArgumentException("pixelwar.stock configuration is required");
            if (maxPixelsPerAction < 1 || (stock.enabled() && maxPixelsPerAction > stock.capacity())) {
                throw new IllegalArgumentException("pixelwar.players.max-pixels-per-action must be between 1 and stock.capacity");
            }
        }
    }
    public record PlayerState(Player player, int stock, int stockCapacity, boolean eliminated) {}
    public record Status(State state, long elapsedMs, Configuration configuration, long attempts,
                         long modifications, List<PlayerState> players) {}
    public record Score(Player player, long cells, double percentage) {}
    public record PlayerMetrics(Player player, long attempts, long modifications, long actions, long actionsWithoutStock,
                                BigInteger pixelsConsumed, BigInteger pixelsRefilled, BigInteger pixelsDiscardedAtCapacity,
                                long conversionsReceived) {}
    public record Latency(double averageNanos, long minimumNanos, long maximumNanos) {}
    public record Duration(long totalNanos, double averageNanos, long minimumNanos, long maximumNanos) {}
    public record Jvm(long heapUsedBytes, long heapMaxBytes, int activeThreads, int availableProcessors, Double processCpuLoad) {}
    public record Metrics(Status simulation, double attemptsPerSecond, double modificationsPerSecond, Latency latency,
                          List<PlayerMetrics> players, Jvm jvm, long totalCells, Map<CellState, Long> cellsByColor,
                          long actions, long actionsWithoutStock, BigInteger pixelsConsumed, BigInteger pixelsRefilled,
                          BigInteger pixelsDiscardedAtCapacity, double actionsPerSecond, double averagePixelsPerAction,
                          long conversionPasses, long conversions, double conversionsPerSecond, long boardChanges,
                          double boardChangesPerSecond, Duration conversionDurationNanos, Board.FrontierMetrics frontiers) {}
    public record Preview(int width, int height, CellState[][] cells) {}

    private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
    private final Board board;
    private final Configuration configuration;
    private final List<Player> players;
    private final Stock[] stocks = new Stock[4];
    private final boolean[] established = new boolean[4], eliminated = new boolean[4];
    private final PlacementStrategy positions;
    private final LongSupplier clock;
    private final ScheduledExecutorService executor;
    private final NeighborConversion conversion = new NeighborConversion();
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private final long[] attemptsByPlayer = new long[4], modificationsByPlayer = new long[4],
            actionsByPlayer = new long[4], emptyActionsByPlayer = new long[4], conversionsByPlayer = new long[4];
    private State state = State.STOPPED;
    private boolean closed;
    private int firstPlayer;
    private long generation, accumulatedNs, runningSince, attempts, modifications, actions, emptyActions;
    private long latencyTotal, latencyMin = Long.MAX_VALUE, latencyMax;
    private long conversionPasses, conversions, conversionTotal, conversionMin = Long.MAX_VALUE, conversionMax;

    public Simulation(Configuration configuration) {
        this(new Board(configuration.width(), configuration.height()), configuration,
                PlacementStrategy.create(configuration, RandomGenerator.getDefault()),
                System::nanoTime, Executors.newSingleThreadScheduledExecutor());
    }

    /** Injection supports deterministic time/positions in tests without real-time sleeps. */
    public Simulation(Board board, Configuration configuration, PlacementStrategy positions,
                      LongSupplier clock, ScheduledExecutorService executor) {
        if (board.width() != configuration.width() || board.height() != configuration.height()) {
            throw new IllegalArgumentException("Board and configuration dimensions must match");
        }
        this.board = board;
        this.configuration = configuration;
        this.positions = positions;
        this.clock = clock;
        this.executor = executor;
        players = List.of(new Player(1, "Player 1", CellState.RED, configuration.intervalNs()),
                new Player(2, "Player 2", CellState.BLUE, configuration.intervalNs()),
                new Player(3, "Player 3", CellState.GREEN, configuration.intervalNs()),
                new Player(4, "Player 4", CellState.YELLOW, configuration.intervalNs()));
        for (int i = 0; i < 4; i++) stocks[i] = new Stock(configuration.stock());
        updateEliminations();
        LOG.info("Board {} x {}, actions every {} ns, max {} pixels/action, conversions {}", board.width(), board.height(),
                configuration.intervalNs(), configuration.maxPixelsPerAction(), configuration.conversionEnabled());
    }

    public synchronized Status start() {
        if (closed) throw new IllegalStateException("Simulation is closed");
        if (state == State.PAUSED) throw new IllegalStateException("Use resume for a paused simulation");
        if (state == State.RUNNING) return status();
        activate();
        LOG.info("Simulation started");
        return status();
    }
    public synchronized Status pause() {
        if (state == State.PAUSED) return status();
        if (state != State.RUNNING) throw new IllegalStateException("Only a running simulation can pause");
        freeze(State.PAUSED);
        LOG.info("Simulation paused");
        return status();
    }
    public synchronized Status resume() {
        if (state == State.RUNNING) return status();
        if (state != State.PAUSED) throw new IllegalStateException("Only a paused simulation can resume");
        activate();
        LOG.info("Simulation resumed");
        return status();
    }
    public synchronized Status stop() {
        freeze(State.STOPPED);
        LOG.info("Simulation stopped");
        return status();
    }
    public synchronized Status reset() {
        freeze(State.STOPPED);
        board.reset();
        for (var stock : stocks) stock.reset();
        Arrays.fill(established, false);
        Arrays.fill(eliminated, false);
        firstPlayer = 0;
        for (var values : List.of(attemptsByPlayer, modificationsByPlayer, actionsByPlayer, emptyActionsByPlayer, conversionsByPlayer)) Arrays.fill(values, 0);
        accumulatedNs = attempts = modifications = actions = emptyActions = latencyTotal = latencyMax = 0;
        conversionPasses = conversions = conversionTotal = conversionMax = 0;
        latencyMin = conversionMin = Long.MAX_VALUE;
        LOG.info("Simulation reset");
        return status();
    }
    private void activate() {
        runningSince = clock.getAsLong();
        state = State.RUNNING;
        long currentGeneration = ++generation;
        tasks.add(executor.scheduleAtFixedRate(() -> playRound(currentGeneration), 0, configuration.intervalNs(), TimeUnit.NANOSECONDS));
    }
    private synchronized void playRound(long expectedGeneration) {
        if (state != State.RUNNING || generation != expectedGeneration) return;
        for (int offset = 0; offset < players.size(); offset++) {
            play((firstPlayer + offset) % players.size(), expectedGeneration);
        }
        firstPlayer = (firstPlayer + 1) % players.size();
    }
    private void freeze(State next) {
        if (state == State.RUNNING) accumulatedNs += clock.getAsLong() - runningSince;
        state = next;
        generation++;
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
    }

    // Package visibility allows deterministic tests of the same action used by the scheduler.
    void play(int index, long expectedGeneration) {
        long before = clock.getAsLong();
        synchronized (this) {
            if (state != State.RUNNING || generation != expectedGeneration || eliminated[index]) return;
            actions++;
            actionsByPlayer[index]++;
            try {
                var stock = stocks[index];
                boolean stockEnabled = configuration.stock().enabled();
                if (stockEnabled) stock.refill(elapsedNs());
                int count = stockEnabled ? Math.min(stock.available(), configuration.maxPixelsPerAction())
                        : configuration.maxPixelsPerAction();
                if (count == 0) { emptyActions++; emptyActionsByPlayer[index]++; }
                for (int n = 0; n < count; n++) {
                    var position = positions.choose(board, players.get(index));
                    boolean changed = board.placeForPlayer(position.x(), position.y(), players.get(index).color());
                    if (changed) {
                        established[index] = true;
                        if (stockEnabled) stock.consumeOne();
                    }
                    attempts++;
                    attemptsByPlayer[index]++;
                    if (changed) { modifications++; modificationsByPlayer[index]++; }
                }
                if (count > 0 && configuration.conversionEnabled()) {
                    long conversionBefore = clock.getAsLong();
                    var received = conversion.convert(board);
                    for (int i = 0; i < 4; i++) {
                        long amount = received.get(players.get(i).color());
                        conversionsByPlayer[i] += amount;
                        conversions += amount;
                    }
                    long duration = clock.getAsLong() - conversionBefore;
                    conversionPasses++;
                    conversionTotal += duration;
                    conversionMin = Math.min(conversionMin, duration);
                    conversionMax = Math.max(conversionMax, duration);
                }
                updateEliminations();
            } catch (RuntimeException exception) {
                LOG.error("Player action failed; stopping simulation", exception);
                freeze(State.STOPPED);
            } finally {
                long duration = clock.getAsLong() - before;
                latencyTotal += duration;
                latencyMin = Math.min(latencyMin, duration);
                latencyMax = Math.max(latencyMax, duration);
            }
        }
    }

    private long elapsedNs() { return accumulatedNs + (state == State.RUNNING ? clock.getAsLong() - runningSince : 0); }
    private void updateEliminations() {
        var counts = board.counts();
        for (int i = 0; i < players.size(); i++) {
            if (counts.get(players.get(i).color()) > 0) established[i] = true;
            else if (established[i]) eliminated[i] = true;
        }
    }
    private Status snapshot(long elapsed) {
        var playerStates = new ArrayList<PlayerState>();
        for (int i = 0; i < 4; i++) playerStates.add(new PlayerState(players.get(i), stocks[i].available(), stocks[i].capacity(), eliminated[i]));
        return new Status(state, elapsed / 1_000_000, configuration, attempts, modifications, List.copyOf(playerStates));
    }
    public synchronized Status status() { return snapshot(elapsedNs()); }
    public synchronized List<Score> scores() {
        var counts = board.counts();
        return players.stream().map(p -> new Score(p, counts.get(p.color()), counts.get(p.color()) * 100.0 / board.totalCells())).toList();
    }
    public synchronized Preview preview() {
        var cells = board.preview(configuration.previewWidth(), configuration.previewHeight());
        return new Preview(cells[0].length, cells.length, cells);
    }
    public synchronized Metrics metrics() {
        long elapsed = elapsedNs();
        double seconds = elapsed / 1_000_000_000.0;
        var playerMetrics = new ArrayList<PlayerMetrics>();
        BigInteger consumed = BigInteger.ZERO, refilled = BigInteger.ZERO, discarded = BigInteger.ZERO;
        for (int i = 0; i < 4; i++) {
            var stock = stocks[i];
            consumed = consumed.add(stock.pixelsConsumed());
            refilled = refilled.add(stock.pixelsRefilled());
            discarded = discarded.add(stock.pixelsDiscardedAtCapacity());
            playerMetrics.add(new PlayerMetrics(players.get(i), attemptsByPlayer[i], modificationsByPlayer[i], actionsByPlayer[i],
                    emptyActionsByPlayer[i], stock.pixelsConsumed(), stock.pixelsRefilled(), stock.pixelsDiscardedAtCapacity(), conversionsByPlayer[i]));
        }
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = ManagementFactory.getOperatingSystemMXBean();
        Double cpu = null;
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            double load = extended.getProcessCpuLoad();
            if (load >= 0) cpu = load;
        }
        var jvm = new Jvm(heap.getUsed(), heap.getMax(), ManagementFactory.getThreadMXBean().getThreadCount(), os.getAvailableProcessors(), cpu);
        long changes = modifications + conversions;
        return new Metrics(snapshot(elapsed), rate(attempts, seconds), rate(modifications, seconds),
                new Latency(actions == 0 ? 0 : (double) latencyTotal / actions, actions == 0 ? 0 : latencyMin, latencyMax),
                List.copyOf(playerMetrics), jvm, board.totalCells(), board.counts(), actions, emptyActions, consumed, refilled, discarded,
                rate(actions, seconds), actions == 0 ? 0 : (double) attempts / actions, conversionPasses, conversions, rate(conversions, seconds),
                changes, rate(changes, seconds), new Duration(conversionTotal, conversionPasses == 0 ? 0 : (double) conversionTotal / conversionPasses,
                conversionPasses == 0 ? 0 : conversionMin, conversionMax), board.frontierMetrics());
    }
    private static double rate(long count, double seconds) { return seconds == 0 ? 0 : count / seconds; }
    @Override public synchronized void close() { freeze(State.STOPPED); closed = true; executor.shutdownNow(); }
}
