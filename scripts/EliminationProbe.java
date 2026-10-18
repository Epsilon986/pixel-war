package org.pixelwar.pixelwar.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Fixed-attempt exploratory comparison of bonus 0 and 8, with identical exploration. */
public class EliminationProbe {
    public static void main(String[] args) throws Exception {
        int size = args.length == 0 ? 100 : Integer.parseInt(args[0]);
        long limit = args.length < 2 ? 2_000_000 : Long.parseLong(args[1]);
        var rows = new ArrayList<String>();
        rows.add("size,seed,bonus,attempts,eliminations,firstEliminationAttempts,secondEliminationAttempts");
        for (long seed : new long[]{11, 22, 33}) for (int bonus : new int[]{0, 8}) {
            var board = new Board(size, size);
            var config = new Simulation.Configuration(size, size, 1, size, size,
                    new Stock.Configuration(100, 1000, 100, 10000, false), 10, true, "frontier", 0.1, 10, bonus, 20);
            var scheduler = new ScheduledThreadPoolExecutor(1) {
                @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long delay, long period, TimeUnit unit) {
                    return super.schedule(() -> {}, 1, TimeUnit.DAYS);
                }
            };
            try (var simulation = new Simulation(board, config, PlacementStrategy.create(config, new Random(seed)), System::nanoTime, scheduler)) {
                simulation.start();
                long first = -1, second = -1;
                int round = 0;
                var status = simulation.status();
                while (status.attempts() < limit && second < 0) {
                    for (int offset = 0; offset < 4; offset++) simulation.play((round + offset) % 4, 1);
                    round++;
                    status = simulation.status();
                    long eliminated = status.players().stream().filter(Simulation.PlayerState::eliminated).count();
                    if (first < 0 && eliminated >= 1) first = status.attempts();
                    if (second < 0 && eliminated >= 2) second = status.attempts();
                }
                long eliminated = status.players().stream().filter(Simulation.PlayerState::eliminated).count();
                String row = size + "," + seed + "," + bonus + "," + status.attempts() + "," + eliminated + "," + first + "," + second;
                rows.add(row);
                System.out.println(row);
            }
        }
        Files.write(Path.of("target", "v3-eliminations.csv"), rows);
    }
}
