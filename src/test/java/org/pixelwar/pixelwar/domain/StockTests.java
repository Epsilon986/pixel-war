package org.pixelwar.pixelwar.domain;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockTests {
    @Test void rechargeRespectsExactDeadlineAndRemainder() {
        var stock = new Stock(new Stock.Configuration(0, 100, 3, 10));
        stock.refill(9); assertEquals(0, stock.available());
        stock.refill(10); assertEquals(3, stock.available());
        stock.refill(39); assertEquals(9, stock.available());
        stock.refill(40); assertEquals(12, stock.available());
        stock.refill(40); assertEquals(12, stock.available());
    }
    @Test void fullPeriodsAreDiscardedWithoutCreatingReserve() {
        var stock = new Stock(new Stock.Configuration(9, 10, 7, 10));
        stock.refill(20);
        assertEquals(10, stock.available());
        assertEquals(BigInteger.ONE, stock.pixelsRefilled());
        assertEquals(BigInteger.valueOf(13), stock.pixelsDiscardedAtCapacity());
        stock.consumeOne();
        stock.refill(29); assertEquals(9, stock.available());
        stock.refill(30); assertEquals(10, stock.available());
        assertEquals(BigInteger.valueOf(19), stock.pixelsDiscardedAtCapacity());
    }
    @Test void extremeCreditProductDoesNotOverflow() {
        var stock = new Stock(new Stock.Configuration(0, 10, Long.MAX_VALUE, 1));
        stock.refill(Long.MAX_VALUE);
        assertEquals(10, stock.available());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).pow(2).subtract(BigInteger.TEN), stock.pixelsDiscardedAtCapacity());
        stock.consumeOne();
        stock.refill(Long.MAX_VALUE);
        assertEquals(9, stock.available());
    }
    @Test void resetAndInvariants() {
        var stock = new Stock(new Stock.Configuration(2, 10, 5, 10));
        stock.consumeOne(); stock.refill(20); stock.consumeOne();
        assertEquals(BigInteger.valueOf(stock.available()), BigInteger.valueOf(2).add(stock.pixelsRefilled()).subtract(stock.pixelsConsumed()));
        stock.reset();
        assertEquals(2, stock.available());
        assertEquals(BigInteger.ZERO, stock.pixelsConsumed());
        assertEquals(BigInteger.ZERO, stock.pixelsRefilled());
        assertEquals(BigInteger.ZERO, stock.pixelsDiscardedAtCapacity());
        stock.refill(10); assertEquals(7, stock.available());
    }
    @Test void validatesStockAndMonotonicTime() {
        assertThrows(IllegalArgumentException.class, () -> new Stock.Configuration(-1, 10, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Stock.Configuration(11, 10, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Stock.Configuration(0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Stock.Configuration(0, 10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Stock.Configuration(0, 10, 1, 0));
        var stock = new Stock(new Stock.Configuration(0, 10, 1, 1));
        assertThrows(IllegalStateException.class, stock::consumeOne);
        stock.refill(5);
        assertThrows(IllegalArgumentException.class, () -> stock.refill(4));
    }
}
