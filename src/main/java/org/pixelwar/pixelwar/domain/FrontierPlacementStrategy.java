package org.pixelwar.pixelwar.domain;

import java.util.Objects;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.random.RandomGenerator;

public final class FrontierPlacementStrategy implements PlacementStrategy {
    private final RandomGenerator random;
    private final double explorationProbability;
    private final int sampleSize;
    private final int weakOpponentBonus, targetHoldActions;
    private final Map<Board, Target[]> targets = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Target {
        CellState color;
        int remainingActions;
    }

    public FrontierPlacementStrategy(RandomGenerator random, double explorationProbability, int sampleSize) {
        this(random, explorationProbability, sampleSize, 8, 20);
    }
    public FrontierPlacementStrategy(RandomGenerator random, double explorationProbability, int sampleSize,
                                     int weakOpponentBonus, int targetHoldActions) {
        PlacementStrategy.validate("frontier", explorationProbability, sampleSize);
        validateBonus(weakOpponentBonus, targetHoldActions);
        this.random = Objects.requireNonNull(random);
        this.explorationProbability = explorationProbability;
        this.sampleSize = sampleSize;
        this.weakOpponentBonus = weakOpponentBonus;
        this.targetHoldActions = targetHoldActions;
    }

    static void validateBonus(int bonus, int holdActions) {
        if (bonus < 0 || bonus > 1_000_000) throw new IllegalArgumentException("pixelwar.players.weak-opponent-bonus must be between 0 and 1000000");
        if (holdActions <= 0) throw new IllegalArgumentException("pixelwar.players.target-hold-actions must be positive");
    }

    @Override public void beginAction(Board board, Simulation.Player player) {
        synchronized (board) {
            if (weakOpponentBonus == 0) return;
            var states = targets.computeIfAbsent(board, ignored -> new Target[4]);
            int index = player.color().ordinal() - 1;
            var target = states[index];
            if (target == null) states[index] = target = new Target();
            if (target.color == null || board.cellCount(target.color) == 0 || target.remainingActions == 0) {
                target.color = weakestOpponent(board, player.color());
                target.remainingActions = targetHoldActions;
            }
            target.remainingActions--;
        }
    }

    private CellState weakestOpponent(Board board, CellState ally) {
        CellState best = null;
        long minimum = Long.MAX_VALUE;
        int ties = 0;
        for (var enemy : CellState.values()) if (enemy != CellState.EMPTY && enemy != ally) {
            long count = board.cellCount(enemy);
            if (count == 0) continue;
            if (count < minimum) { best = enemy; minimum = count; ties = 1; }
            else if (count == minimum && random.nextInt(++ties) == 0) best = enemy;
        }
        return best;
    }

    @Override public void reset(Board board) { targets.remove(board); }

    CellState priorityTarget(Board board, CellState ally) {
        synchronized (board) {
            var states = targets.get(board);
            var target = states == null ? null : states[ally.ordinal() - 1];
            return target == null ? null : target.color;
        }
    }

    @Override public Position choose(Board board, Simulation.Player player) {
        synchronized (board) {
            if (random.nextDouble() < explorationProbability || board.frontierSize(player.color()) == 0) {
                var empty = board.randomEmptyPosition(random);
                if (empty != null) return empty;
                return new Position(random.nextInt(board.width()), random.nextInt(board.height()));
            }
            Position best = null;
            int bestScore = Integer.MIN_VALUE, ties = 0;
            for (int i = 0; i < sampleSize; i++) {
                var candidate = board.randomFrontierPosition(player.color(), random);
                int score = conquestScore(board, candidate, player.color());
                if (score > bestScore) { best = candidate; bestScore = score; ties = 1; }
                else if (score == bestScore && random.nextInt(++ties) == 0) best = candidate;
            }
            return best;
        }
    }

    int conquestScore(Board board, Position target, CellState ally) {
        synchronized (board) {
            int base = score(board, target, ally);
            var enemy = board.get(target.x(), target.y());
            if (weakOpponentBonus == 0 || enemy == CellState.EMPTY || enemy == ally) return base;
            long maximum = 0;
            for (var color : CellState.values()) if (color != CellState.EMPTY && color != ally) maximum = Math.max(maximum, board.cellCount(color));
            long count = board.cellCount(enemy);
            int weakness = maximum == 0 ? 0 : (int) Math.round(weakOpponentBonus * (1.0 - count / (double) maximum));
            return base + weakness + (enemy == priorityTarget(board, ally) ? weakOpponentBonus : 0);
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
