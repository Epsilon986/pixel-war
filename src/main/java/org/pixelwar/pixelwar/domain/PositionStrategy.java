package org.pixelwar.pixelwar.domain;

import java.util.concurrent.ThreadLocalRandom;

/** Compatibility alias for deterministic V2 fixtures; new strategies implement PlacementStrategy. */
@FunctionalInterface
public interface PositionStrategy extends PlacementStrategy {

    static PositionStrategy random() {
        return new RandomPlacementStrategy(ThreadLocalRandom.current())::choose;
    }
}
