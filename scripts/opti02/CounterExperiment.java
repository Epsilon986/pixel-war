package org.pixelwar.pixelwar.domain;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.pixelwar.pixelwar.HealthController;
import org.pixelwar.pixelwar.HttpPerformanceFilter;
import org.pixelwar.pixelwar.SimulationController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Common experiment harness. Never changes a game rule or the production API. */
public final class CounterExperiment {
    private static final Field ACTIONS = field(Simulation.class, "actions");
    private static final Field STATE = field(Simulation.class, "state");
    private static final Field CELLS = field(Board.class, "cells");
    private static final Field POSITIONS = field(Simulation.class, "positions");
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOC = (com.sun.management.ThreadMXBean) THREADS;
    private static final java.lang.management.MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final AtomicLong PEAK = new AtomicLong();
    private static final ScheduledExecutorService SAMPLER = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "opti-02-heap-sampler"); thread.setDaemon(true); return thread;
    });
    private static Board serverBoard;
    private static Simulation serverSimulation;
    private static Path serverProfile;
    private static Recording recording;

    private record Game(Board board, Simulation simulation, FrontierPlacementStrategy strategy) implements AutoCloseable {
        public void close() { simulation.close(); }
    }
    public static void main(String[] args) throws Exception {
        // mode size initial seed budget warmupSeconds output.json profile.jfr-or-none
        String mode = args[0], initial = args[2];
        int size = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[3]), budget = Long.parseLong(args[4]);
        double warmupSeconds = Double.parseDouble(args[5]);
        Path output = Path.of(args[6]);
        THREADS.setThreadCpuTimeEnabled(true);
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        SAMPLER.scheduleAtFixedRate(() -> PEAK.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(), Math::max), 0, 10, TimeUnit.MILLISECONDS);
        if (mode.equals("server")) {
            var game = create(size, initial, seed, true);
            serverBoard = game.board(); serverSimulation = game.simulation();
            if (!args[7].equals("none")) serverProfile = Path.of(args[7]);
            var app = new SpringApplication(ServerConfiguration.class);
            app.run("--server.port=" + budget, "--spring.application.name=opti-02");
            return;
        }
        if (warmupSeconds > 0) warmup(size, initial, seed, warmupSeconds);
        System.gc();
        long preparationStart = System.nanoTime();
        try (var game = create(size, initial, seed, false)) {
            long preparationNs = System.nanoTime() - preparationStart;
            var before = business(game);
            var layout = layout(game.board());
            System.gc(); // Drop preparation garbage before timing, identically in both versions.
            game.simulation().start();
            if (!args[7].equals("none")) recording = startRecording();
            PEAK.set(MEMORY.getHeapMemoryUsage().getUsed());
            long thread = Thread.currentThread().threadId();
            long cpuStart = THREADS.getCurrentThreadCpuTime(), allocatedStart = ALLOC.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            runActions(game.simulation(), budget);
            long elapsed = System.nanoTime() - start;
            long cpu = THREADS.getCurrentThreadCpuTime() - cpuStart, allocated = ALLOC.getThreadAllocatedBytes(thread) - allocatedStart;
            PEAK.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(), Math::max);
            long peak = PEAK.get();
            game.simulation().pause();
            if (recording != null) { recording.stop(); recording.dump(Path.of(args[7])); recording.close(); }
            var after = business(game);
            long actions = (long) after.get("actions") - (long) before.get("actions");
            long attempts = (long) after.get("attempts") - (long) before.get("attempts");
            long modifications = (long) after.get("modifications") - (long) before.get("modifications");
            var result = map("mode", mode, "size", size, "initial", initial, "seed", seed,
                    "requestedActions", budget, "executedActions", actions, "warmupSeconds", warmupSeconds,
                    "preparationNanos", preparationNs, "elapsedNanos", elapsed, "cpuNanos", cpu,
                    "allocatedBytes", allocated, "allocatedBytesPerAction", allocated / (double) actions,
                    "peakObservedHeapBytes", peak, "heapSamplePeriodMillis", 10,
                    "attemptsPerSecond", attempts * 1e9 / elapsed, "modificationsPerSecond", modifications * 1e9 / elapsed,
                    "before", before, "after", after, "spatialSha256", spatialHash(game.board()),
                    "colorsLayout", layout, "runtime", runtime(), "errors", List.of());
            if (actions != budget) throw new IllegalStateException("Executed action count differs from budget");
            Files.writeString(output, json(result));
        }
    }
    private static void warmup(int size, String initial, long seed, double seconds) throws Exception {
        try (var game = create(size, initial, seed, false)) {
            game.simulation().start();
            long deadline = System.nanoTime() + (long) (seconds * 1e9);
            while (System.nanoTime() < deadline) runActions(game.simulation(), ACTIONS.getLong(game.simulation()) + 1000);
        }
    }
    private static Game create(int size, String initial, long seed, boolean scheduled) {
        var board = new Board(size, size);
        if (initial.equals("full")) {
            var random = new Random(seed + 1000);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) board.place(x, y, CellState.values()[1 + random.nextInt(4)]);
        }
        var config = new Simulation.Configuration(size, size, 10, 500, 500,
                new Stock.Configuration(100, 1000, 100, 10000, false), 10, true, "frontier", .1, 10, 8, 20);
        var strategy = new FrontierPlacementStrategy(new Random(seed), .1, 10, 8, 20);
        ScheduledExecutorService scheduler = scheduled ? Executors.newSingleThreadScheduledExecutor() : new ScheduledThreadPoolExecutor(1) {
            @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long delay, long period, TimeUnit unit) {
                return super.schedule(() -> {}, 1, TimeUnit.DAYS);
            }
        };
        return new Game(board, new Simulation(board, config, strategy, System::nanoTime, scheduler), strategy);
    }
    private static void runActions(Simulation simulation, long total) throws Exception {
        int first = 0;
        while (ACTIONS.getLong(simulation) < total) {
            for (int offset = 0; offset < 4 && ACTIONS.getLong(simulation) < total; offset++) simulation.play((first + offset) % 4, 1);
            first = (first + 1) % 4;
            if (first == 0 && STATE.get(simulation) != Simulation.State.RUNNING) throw new IllegalStateException("Unexpected engine stop");
        }
    }
    private static Map<String, Object> business(Game game) {
        var metrics = game.simulation().metrics();
        var players = new ArrayList<Object>();
        for (int i = 0; i < 4; i++) {
            var player = metrics.players().get(i);
            var state = metrics.simulation().players().get(i);
            players.add(map("color", player.player().color().name(), "stock", state.stock(), "eliminated", state.eliminated(),
                    "attempts", player.attempts(), "modifications", player.modifications(), "actions", player.actions(),
                    "actionsWithoutStock", player.actionsWithoutStock(), "pixelsConsumed", player.pixelsConsumed().toString(),
                    "pixelsRefilled", player.pixelsRefilled().toString(), "pixelsDiscarded", player.pixelsDiscardedAtCapacity().toString(),
                    "conversionsReceived", player.conversionsReceived(), "priorityTarget", game.strategy().priorityTarget(game.board(), player.player().color())));
        }
        return map("actions", metrics.actions(), "attempts", metrics.simulation().attempts(), "modifications", metrics.simulation().modifications(),
                "conversions", metrics.conversions(), "conversionPasses", metrics.conversionPasses(), "boardChanges", metrics.boardChanges(),
                "counts", metrics.cellsByColor(), "frontierSizes", metrics.frontiers().sizes(), "players", players);
    }
    private static Map<String, Object> layout(Board board) throws Exception {
        Object colors = CELLS.get(board);
        long bytes = StorageAgent.instrumentation.getObjectSize(colors);
        int arrays = 1;
        if (colors instanceof Object[] rows) for (var row : rows) { bytes += StorageAgent.instrumentation.getObjectSize(row); arrays++; }
        return map("type", colors.getClass().getTypeName(), "arrays", arrays, "totalArrayBytes", bytes,
                "cells", board.totalCells(), "measurement", "Instrumentation.getObjectSize; color arrays only; shared enum instances excluded");
    }
    private static String spatialHash(Board board) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < board.height(); y++) for (int x = 0; x < board.width(); x++) digest.update((byte) board.get(x, y).ordinal());
        return HexFormat.of().formatHex(digest.digest());
    }
    private static Map<String, Object> runtime() {
        var diagnostic = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        return map("javaVersion", System.getProperty("java.runtime.version"), "vm", System.getProperty("java.vm.name"),
                "flags", ManagementFactory.getRuntimeMXBean().getInputArguments(), "os", System.getProperty("os.name"),
                "gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.GarbageCollectorMXBean::getName).toList(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory(), "processors", Runtime.getRuntime().availableProcessors(),
                "compressedOops", diagnostic.getVMOption("UseCompressedOops").getValue(),
                "objectAlignment", diagnostic.getVMOption("ObjectAlignmentInBytes").getValue());
    }
    private static Recording startRecording() throws Exception {
        var value = new Recording(Configuration.getConfiguration("profile"));
        value.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1)).withStackTrace();
        value.enable("jdk.ObjectAllocationSample").withStackTrace();
        value.start();
        return value;
    }
    private static Field field(Class<?> type, String name) {
        try { var value = type.getDeclaredField(name); value.setAccessible(true); return value; }
        catch (ReflectiveOperationException exception) { throw new ExceptionInInitializerError(exception); }
    }
    public static LinkedHashMap<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i].toString(), pairs[i + 1]);
        return result;
    }
    public static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> values) return values.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> values) { var items = new ArrayList<String>(); for (var item : values) items.add(json(item)); return String.join(",", items).transform(text -> "[" + text + "]"); }
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SimulationController.class, HttpPerformanceFilter.class, HealthController.class, ExperimentEndpoints.class})
    public static class ServerConfiguration {
        @Bean(destroyMethod = "close") public Simulation simulation() { return serverSimulation; }
    }
    @RestController
    public static class ExperimentEndpoints {
        @PostMapping(value = "/experiment/begin", produces = "application/json")
        public String begin() throws Exception {
            PEAK.set(MEMORY.getHeapMemoryUsage().getUsed());
            if (serverProfile != null) recording = startRecording();
            return snapshot();
        }
        @GetMapping(value = "/experiment/snapshot", produces = "application/json")
        public String snapshot() throws Exception {
            var strategy = (FrontierPlacementStrategy) POSITIONS.get(serverSimulation);
            var metrics = serverSimulation.metrics();
            var performance = serverSimulation.performance().measurements();
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return json(map("business", business(new Game(serverBoard, serverSimulation, strategy)),
                    "elapsedMs", metrics.simulation().elapsedMs(), "activeElapsedNanos", performance.elapsedNanos(),
                    "engineBlockedNanos", performance.engineBlockedNanos(), "failure", serverSimulation.performance().failure(), "cpuNanos", performance.cpuNanos(),
                    "engineAllocatedBytes", performance.allocatedBytesPerAction() == null ? null : performance.allocatedBytesPerAction() * performance.actions(),
                    "processCpuNanos", os.getProcessCpuTime(), "peakObservedHeapBytes", PEAK.get(),
                    "colorsLayout", layout(serverBoard), "runtime", runtime()));
        }
        @PostMapping(value = "/experiment/finish", produces = "application/json")
        public String finish() throws Exception {
            String result = snapshot();
            if (recording != null) { recording.stop(); recording.dump(serverProfile); recording.close(); recording = null; }
            return result;
        }
    }
}
