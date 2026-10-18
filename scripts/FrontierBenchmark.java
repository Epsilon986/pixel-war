package org.pixelwar.pixelwar.domain;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Reproducible exploratory engine comparison. Scheduler callbacks are driven by this thread. */
public class FrontierBenchmark {
    private static final long ATTEMPTS = 50_000;
    private static final int[] COLORS = {0x080d16, 0xef5350, 0x42a5f5, 0x66bb6a, 0xffee58};

    public static void main(String[] args) throws Exception {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        Path output = Path.of("target", "v3-benchmark");
        Files.createDirectories(output);
        var rows = new ArrayList<String>();
        rows.add("mode,initial,strategy,seed,size,seconds,attempts,actionsPerSecond,attemptsPerSecond,modificationsPerSecond,conversionsPerSecond,changesPerSecond,actionMeanUs,maintenanceMeanUs,frontierCells,frontierStorageBytes,heapUsedBytes,threadCpuPercent,occupiedNeighborAgreement,redPercent,bluePercent,greenPercent,yellowPercent");
        var evolution = new ArrayList<String>();
        evolution.add("initial,strategy,seed,attempts,redPercent,bluePercent,greenPercent,yellowPercent,occupiedNeighborAgreement");
        for (long seed : new long[]{11, 22, 33}) for (boolean full : new boolean[]{false, true}) {
            // Alternate ordering to reduce systematic first-run bias within this single JVM.
            String[] strategies = seed == 22 ? new String[]{"frontier", "random"} : new String[]{"random", "frontier"};
            for (String strategy : strategies) {
                measure(size, seed, full, strategy, true, output, rows, evolution);
                measure(size, seed, full, strategy, false, output, rows, evolution);
            }
        }
        Files.write(output.resolve("results.csv"), rows);
        Files.write(output.resolve("evolution.csv"), evolution);
        System.out.println("Results: " + output.toAbsolutePath());
    }

    private static void measure(int size, long seed, boolean full, String strategy, boolean timed,
                                Path output, ArrayList<String> rows, ArrayList<String> evolution) throws Exception {
        var board = new Board(size, size);
        if (full) {
            var initial = new Random(seed + 1000);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                board.place(x, y, CellState.values()[1 + initial.nextInt(4)]);
            }
        }
        var config = new Simulation.Configuration(size, size, 10, size, size,
                new Stock.Configuration(100, 1000, 100, 10000, false), 10, true, strategy, 0.1, 10);
        var scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
                // No callback runs during the measurement. play uses the exact production action.
                return super.schedule(() -> {}, 1, TimeUnit.DAYS);
            }
        };
        try (var simulation = new Simulation(board, config, PlacementStrategy.create(config, new Random(seed)), System::nanoTime, scheduler)) {
            simulation.start();
            int round = 0;
            if (timed) {
                long warmupEnd = System.nanoTime() + 300_000_000L;
                while (System.nanoTime() < warmupEnd) playRound(simulation, round++);
            } else snapshot(board, full, strategy, seed, simulation.status().attempts(), evolution);
            var before = simulation.metrics();
            var threads = ManagementFactory.getThreadMXBean();
            long cpuBefore = threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : -1;
            long start = System.nanoTime(), checkpoint = 10_000;
            if (timed) {
                while (System.nanoTime() - start < 1_000_000_000L) playRound(simulation, round++);
            } else {
                // Equal total attempted placements, including rejected/no-op placements.
                while (simulation.status().attempts() < ATTEMPTS) {
                    for (int offset = 0; offset < 4 && simulation.status().attempts() < ATTEMPTS; offset++) {
                        simulation.play((round + offset) % 4, 1);
                    }
                    round++;
                    if (simulation.status().attempts() >= checkpoint) {
                        snapshot(board, full, strategy, seed, simulation.status().attempts(), evolution);
                        checkpoint += 10_000;
                    }
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            long cpuAfter = threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : -1;
            simulation.pause();
            var after = simulation.metrics();
            long actions = after.actions() - before.actions();
            long updates = after.frontiers().updates() - before.frontiers().updates();
            double meanUs = actions == 0 ? 0 : (after.latency().averageNanos() * after.actions()
                    - before.latency().averageNanos() * before.actions()) / actions / 1000;
            double maintenanceUs = updates == 0 ? 0 : (after.frontiers().totalNanos() - before.frontiers().totalNanos()) / (double) updates / 1000;
            String row = String.format(Locale.ROOT,
                    "%s,%s,%s,%d,%d,%.6f,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.3f,%.3f,%d,%d,%d,%.2f,%.6f,%s",
                    timed ? "performance" : "gameplay", full ? "full" : "empty", strategy, seed, size, seconds,
                    after.simulation().attempts() - before.simulation().attempts(), actions / seconds,
                    (after.simulation().attempts() - before.simulation().attempts()) / seconds,
                    (after.simulation().modifications() - before.simulation().modifications()) / seconds,
                    (after.conversions() - before.conversions()) / seconds, (after.boardChanges() - before.boardChanges()) / seconds,
                    meanUs, maintenanceUs, after.frontiers().sizes().values().stream().mapToInt(Integer::intValue).sum(),
                    after.frontiers().storageBytes(), after.jvm().heapUsedBytes(),
                    cpuBefore < 0 || cpuAfter < 0 ? -1 : (cpuAfter - cpuBefore) / (seconds * 1e9) * 100,
                    agreement(board), shares(board));
            rows.add(row);
            if (!timed) writeImage(board, output.resolve((full ? "full" : "empty") + "-" + strategy + "-" + seed + ".png"));
        }
    }

    private static void playRound(Simulation simulation, int round) {
        for (int offset = 0; offset < 4; offset++) simulation.play((round + offset) % 4, 1);
    }
    private static String shares(Board board) {
        var counts = board.counts();
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", percent(board, counts.get(CellState.RED)),
                percent(board, counts.get(CellState.BLUE)), percent(board, counts.get(CellState.GREEN)), percent(board, counts.get(CellState.YELLOW)));
    }
    private static double percent(Board board, long count) { return count * 100.0 / board.totalCells(); }
    private static void snapshot(Board board, boolean full, String strategy, long seed, long attempts, ArrayList<String> evolution) {
        evolution.add(String.format(Locale.ROOT, "%s,%s,%d,%d,%s,%.6f", full ? "full" : "empty", strategy, seed, attempts, shares(board), agreement(board)));
    }
    private static double agreement(Board board) {
        long equal = 0, occupied = 0;
        for (int y = 0; y < board.height(); y++) for (int x = 0; x < board.width(); x++) {
            var color = board.get(x, y);
            if (color == CellState.EMPTY) continue;
            if (x + 1 < board.width() && board.get(x + 1, y) != CellState.EMPTY) {
                occupied++; if (board.get(x + 1, y) == color) equal++;
            }
            if (y + 1 < board.height() && board.get(x, y + 1) != CellState.EMPTY) {
                occupied++; if (board.get(x, y + 1) == color) equal++;
            }
        }
        return occupied == 0 ? 0 : equal / (double) occupied;
    }
    private static void writeImage(Board board, Path path) throws Exception {
        var image = new BufferedImage(board.width(), board.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < board.height(); y++) for (int x = 0; x < board.width(); x++) image.setRGB(x, y, COLORS[board.get(x, y).ordinal()]);
        ImageIO.write(image, "png", path.toFile());
    }
}
