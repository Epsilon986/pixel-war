import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import org.pixelwar.pixelwar.domain.Board;
import org.pixelwar.pixelwar.domain.CellState;
import org.pixelwar.pixelwar.domain.NeighborConversion;

/** Exploratory detection comparison, not a JMH benchmark or simulation throughput measurement. */
class IncrementalConversionProbe {
    public static void main(String[] args) {
        int size = args.length == 0 ? 4000 : Integer.parseInt(args[0]);
        var board = new Board(size, size);
        var conversion = new NeighborConversion();
        var random = new Random(20261009L);
        long fullNs = 0, incrementalNs = 0;
        int warmup = 10, measured = 30;
        for (int pass = 0; pass < warmup + measured; pass++) {
            for (int n = 0; n < 10; n++) {
                board.place(random.nextInt(size), random.nextInt(size), CellState.values()[1 + random.nextInt(4)]);
            }
            long before = System.nanoTime();
            var full = fullScan(board);
            long fullDuration = System.nanoTime() - before;
            before = System.nanoTime();
            var incremental = conversion.detect(board);
            long incrementalDuration = System.nanoTime() - before;
            if (!new HashSet<>(full).equals(new HashSet<>(incremental))) throw new AssertionError("Detection mismatch");
            conversion.convert(board);
            if (pass >= warmup) {
                fullNs += fullDuration;
                incrementalNs += incrementalDuration;
            }
        }
        System.out.printf(Locale.ROOT,
                "Board=%dx%d warmup=%d measured=%d placements/pass=10 fullDetectionMeanMs=%.6f incrementalDetectionMeanMs=%.6f%n",
                size, size, warmup, measured, fullNs / measured / 1e6, incrementalNs / measured / 1e6);
    }

    private static ArrayList<NeighborConversion.Change> fullScan(Board board) {
        synchronized (board) {
            var changes = new ArrayList<NeighborConversion.Change>();
            for (int y = 1; y < board.height() - 1; y++) for (int x = 1; x < board.width() - 1; x++) {
                var color = board.get(x, y - 1);
                if (color != CellState.EMPTY && board.get(x, y) != color
                        && board.get(x, y + 1) == color && board.get(x - 1, y) == color && board.get(x + 1, y) == color) {
                    changes.add(new NeighborConversion.Change(x, y, color));
                }
            }
            return changes;
        }
    }
}
