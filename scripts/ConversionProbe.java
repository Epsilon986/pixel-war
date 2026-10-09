import org.pixelwar.pixelwar.domain.Board;
import org.pixelwar.pixelwar.domain.CellState;
import org.pixelwar.pixelwar.domain.NeighborConversion;

/** Exploratory prepared-board probe, not a JMH microbenchmark or a random game. */
class ConversionProbe {
    public static void main(String[] args) {
        int size = args.length == 0 ? 500 : Integer.parseInt(args[0]);
        if (size < 3) throw new IllegalArgumentException("Board size must be >=3");
        var conversion = new NeighborConversion();
        long total = 0, min = Long.MAX_VALUE, max = 0, converted = 0;
        for (int iteration = -5; iteration < 20; iteration++) {
            var board = new Board(size, size);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                board.place(x, y, (x + y) % 2 == 0 ? CellState.RED : CellState.BLUE);
            }
            long before = System.nanoTime();
            var result = conversion.convert(board);
            long duration = System.nanoTime() - before;
            long count = result.values().stream().mapToLong(Long::longValue).sum();
            if (count != (long) (size - 2) * (size - 2)) throw new AssertionError("Incorrect conversion count");
            if (iteration >= 0) {
                total += duration; min = Math.min(min, duration); max = Math.max(max, duration); converted += count;
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "{\"boardSize\":%d,\"warmupPasses\":5,\"measuredPasses\":20,\"conversions\":%d,\"averageMs\":%.3f,\"minimumMs\":%.3f,\"maximumMs\":%.3f}%n",
                size, converted, total / 20.0 / 1e6, min / 1e6, max / 1e6);
    }
}
