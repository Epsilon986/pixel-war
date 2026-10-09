package org.pixelwar.pixelwar.domain;

import java.util.Objects;
import java.util.random.RandomGenerator;

public final class FrontierPlacementStrategy implements PlacementStrategy {
    private final RandomGenerator random;
    private final double explorationProbability;
    private final int sampleSize;

    public FrontierPlacementStrategy(RandomGenerator random, double explorationProbability, int sampleSize) {
        PlacementStrategy.validate("frontier", explorationProbability, sampleSize);
        this.random = Objects.requireNonNull(random);
        this.explorationProbability = explorationProbability;
        this.sampleSize = sampleSize;
    }

    @Override public Position choose(Board board, Simulation.Player player) {
        synchronized (board) {
            if (random.nextDouble() < explorationProbability || board.frontierSize(player.color()) == 0) {
                return new Position(random.nextInt(board.width()), random.nextInt(board.height()));
            }
            Position best = null;
            int bestScore = Integer.MIN_VALUE, ties = 0;
            for (int i = 0; i < sampleSize; i++) {
                var candidate = board.randomFrontierPosition(player.color(), random);
                int score = score(board, candidate, player.color());
                if (score > bestScore) { best = candidate; bestScore = score; ties = 1; }
                else if (score == bestScore && random.nextInt(++ties) == 0) best = candidate;
            }
            return best;
        }
    }

    int score(Board board, Position target, CellState ally) {
        synchronized (board) {
            int x = target.x(), y = target.y();
            int score = board.get(x, y) != CellState.EMPTY && board.get(x, y) != ally ? 1 : 0;
            if (x > 0 && board.get(x - 1, y) == ally) score += 2;
            if (x + 1 < board.width() && board.get(x + 1, y) == ally) score += 2;
            if (y > 0 && board.get(x, y - 1) == ally) score += 2;
            if (y + 1 < board.height() && board.get(x, y + 1) == ally) score += 2;
            score += 8 * (potential(board, x - 1, y, target, ally) + potential(board, x + 1, y, target, ally)
                    + potential(board, x, y - 1, target, ally) + potential(board, x, y + 1, target, ally));
            return score;
        }
    }

    private int potential(Board board, int x, int y, Position target, CellState ally) {
        if (x <= 0 || x >= board.width() - 1 || y <= 0 || y >= board.height() - 1 || board.get(x, y) == ally) return 0;
        return after(board, x - 1, y, target, ally) == ally && after(board, x + 1, y, target, ally) == ally
                && after(board, x, y - 1, target, ally) == ally && after(board, x, y + 1, target, ally) == ally ? 1 : 0;
    }
    private CellState after(Board board, int x, int y, Position target, CellState ally) {
        return x == target.x() && y == target.y() ? ally : board.get(x, y);
    }
}
