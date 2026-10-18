package org.pixelwar.pixelwar.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceTrackerTests {
    private static final class Probe implements PerformanceTracker.Probe {
        long cpu = 100, allocated = 1000, blocked = 10, heap = 2000;
        public long cpuNanos(long id) { return cpu; }
        public long allocatedBytes(long id) { return allocated; }
        public long blockedMillis(long id) { return blocked; }
        public long heapBytes() { return heap; }
    }
    @Test void deltasPeakAndPauseExcludeResourcesSpentOutsideEngineActivity() {
        var probe = new Probe();
        var tracker = new PerformanceTracker(probe);
        tracker.beginAction(100);
        probe.cpu += 50; probe.allocated += 400; probe.blocked += 2; probe.heap = 5000;
        tracker.tick(100_000_100);
        probe.heap = 3000;
        var result = tracker.snapshot(1_000_000_100, 4, 40);
        assertEquals(1_000_000_000, result.elapsedNanos());
        assertEquals(40, result.attemptsPerSecond());
        assertEquals(50L, result.cpuNanos());
        assertEquals(100.0, result.allocatedBytesPerAction());
        assertEquals(2_000_000L, result.engineBlockedNanos());
        assertEquals(5000, result.peakObservedHeapBytes());
        tracker.pause(1_000_000_100);
        probe.cpu += 1000; probe.allocated += 10000; probe.blocked += 100;
        assertEquals(result, tracker.snapshot(1_000_000_100, 4, 40));
        tracker.beginAction(1_000_000_100);
        probe.cpu += 10; probe.allocated += 40;
        tracker.pause(2_000_000_100L);
        var resumed = tracker.snapshot(2_000_000_100L, 5, 50);
        assertEquals(60L, resumed.cpuNanos());
        assertEquals(88.0, resumed.allocatedBytesPerAction());
        assertEquals(2_000_000L, resumed.engineBlockedNanos());
    }
    @Test void unsupportedProbesAreNullAndResetStartsANewHeapObservation() {
        var probe = new Probe(); probe.cpu = probe.allocated = probe.blocked = -1;
        var tracker = new PerformanceTracker(probe);
        tracker.beginAction(0);
        var result = tracker.snapshot(1_000_000, 1, 10);
        assertNull(result.cpuNanos());
        assertNull(result.allocatedBytesPerAction());
        assertNull(result.engineBlockedNanos());
        probe.heap = 1000;
        tracker.reset();
        result = tracker.snapshot(0, 0, 0);
        assertEquals(1000, result.peakObservedHeapBytes());
        assertEquals(0, result.elapsedNanos());
        assertEquals(0, result.attemptsPerSecond());
    }
}
