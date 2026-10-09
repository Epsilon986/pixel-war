package org.pixelwar.pixelwar.domain;

import java.math.BigInteger;

/** Accessed under the simulation monitor. Recharge depends exclusively on active time. */
public final class Stock {
    public record Configuration(int initial, int capacity, long refillAmount, long refillIntervalNs, boolean enabled) {
        public Configuration(int initial, int capacity, long refillAmount, long refillIntervalNs) {
            this(initial, capacity, refillAmount, refillIntervalNs, true);
        }
        public Configuration {
            if (capacity < 1) throw new IllegalArgumentException("pixelwar.stock.capacity must be positive");
            if (initial < 0 || initial > capacity) throw new IllegalArgumentException("pixelwar.stock.initial must be between 0 and capacity");
            if (refillAmount < 1) throw new IllegalArgumentException("pixelwar.stock.refill-amount must be positive");
            if (refillIntervalNs < 1) throw new IllegalArgumentException("pixelwar.stock.refill-interval-ns must be positive");
        }
    }

    private final Configuration configuration;
    private int available;
    private long lastRechargeNs;
    private BigInteger refilled = BigInteger.ZERO;
    private BigInteger discarded = BigInteger.ZERO;
    private BigInteger consumed = BigInteger.ZERO;

    public Stock(Configuration configuration) {
        this.configuration = configuration;
        reset();
    }

    public void refill(long activeNs) {
        if (activeNs < lastRechargeNs) throw new IllegalArgumentException("Active time must be monotonic");
        long periods = (activeNs - lastRechargeNs) / configuration.refillIntervalNs();
        if (periods == 0) return;
        // This product is <= activeNs - lastRechargeNs, so it cannot overflow.
        lastRechargeNs += periods * configuration.refillIntervalNs();
        // Credits can exceed long even though stock itself is bounded by an int capacity.
        var credits = BigInteger.valueOf(periods).multiply(BigInteger.valueOf(configuration.refillAmount()));
        int accepted = credits.min(BigInteger.valueOf(configuration.capacity() - available)).intValueExact();
        available += accepted;
        refilled = refilled.add(BigInteger.valueOf(accepted));
        discarded = discarded.add(credits.subtract(BigInteger.valueOf(accepted)));
    }

    public void consumeOne() {
        if (available == 0) throw new IllegalStateException("No pixels available");
        available--;
        consumed = consumed.add(BigInteger.ONE);
    }

    public void reset() {
        available = configuration.initial();
        lastRechargeNs = 0;
        refilled = discarded = consumed = BigInteger.ZERO;
    }

    public int available() { return available; }
    public int capacity() { return configuration.capacity(); }
    public BigInteger pixelsRefilled() { return refilled; }
    public BigInteger pixelsDiscardedAtCapacity() { return discarded; }
    public BigInteger pixelsConsumed() { return consumed; }
}
