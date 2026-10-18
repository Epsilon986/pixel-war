package org.pixelwar.pixelwar.domain;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** V2 reference: sample empty cells uniformly until the board is full. */
public final class RandomPlacementStrategy implements PlacementStrategy {
    private final RandomGenerator random;
    public RandomPlacementStrategy(RandomGenerator random) { this.random = Objects.requireNonNull(random); }

    @Override public Position choose(Board board, Simulation.Player player) {
        synchronized (board) {
            var empty = board.randomEmptyPosition(random);
            return empty != null ? empty : new Position(random.nextInt(board.width()), random.nextInt(board.height()));
        }
    }
}
