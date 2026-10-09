package org.pixelwar.pixelwar.domain;

import java.util.concurrent.ThreadLocalRandom;

@FunctionalInterface
public interface PositionStrategy {
    record Position(int x, int y) {}
    Position choose(Board board, Simulation.Player player);

    static PositionStrategy random() {
        return (board, player) -> {
            var empty = board.randomEmptyPosition();
            return empty != null ? empty : new Position(ThreadLocalRandom.current().nextInt(board.width()),
                    ThreadLocalRandom.current().nextInt(board.height()));
        };
    }
}
