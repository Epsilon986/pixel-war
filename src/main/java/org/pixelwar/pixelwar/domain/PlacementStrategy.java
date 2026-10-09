package org.pixelwar.pixelwar.domain;

import java.util.random.RandomGenerator;

@FunctionalInterface
public interface PlacementStrategy {
    record Position(int x, int y) {}
    Position choose(Board board, Simulation.Player player);

    static void validate(String strategy, double explorationProbability, int sampleSize) {
        if (!"random".equals(strategy) && !"frontier".equals(strategy)) {
            throw new IllegalArgumentException("pixelwar.players.strategy must be random or frontier");
        }
        if (!Double.isFinite(explorationProbability) || explorationProbability < 0 || explorationProbability > 1) {
            throw new IllegalArgumentException("pixelwar.players.exploration-probability must be finite and between 0 and 1");
        }
        if (sampleSize <= 0) throw new IllegalArgumentException("pixelwar.players.frontier-sample-size must be positive");
    }

    static PlacementStrategy create(Simulation.Configuration config, RandomGenerator random) {
        return "random".equals(config.strategy()) ? new RandomPlacementStrategy(random)
                : new FrontierPlacementStrategy(random, config.explorationProbability(), config.frontierSampleSize());
    }
}
