package org.pixelwar.pixelwar;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.pixelwar.pixelwar.domain.PerformanceTracker;
import org.pixelwar.pixelwar.domain.Simulation;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpPerformanceFilterTests {
    @Test void requestsAfterTrialCompletionDoNotDiluteItsPercentiles() throws Exception {
        var simulation = mock(Simulation.class);
        when(simulation.trialCompletedAtNanos()).thenReturn(System.nanoTime() - 1_000_000);
        var filter = new HttpPerformanceFilter(simulation);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/performance"), new MockHttpServletResponse(), (request, response) -> {});
        assertEquals(0, filter.snapshot().reads().samples());
        when(simulation.trialCompletedAtNanos()).thenReturn(null);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/performance"), new MockHttpServletResponse(), (request, response) -> {});
        assertEquals(1, filter.snapshot().reads().samples());
    }

    @Test void realMonitorContentionIsProfiledAndIncludedInHttpLatency() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PerformanceTracker.jvmProbe().blockedMillis(Thread.currentThread().threadId()) >= 0);
        var lock = new Object();
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(() -> {
            synchronized (lock) {
                acquired.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
        holder.start();
        assertTrue(acquired.await(2, TimeUnit.SECONDS));
        var releaser = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            release.countDown();
        });
        try {
            var filter = new HttpPerformanceFilter(null);
            releaser.start();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/performance"), new MockHttpServletResponse(), (request, response) -> {
                synchronized (lock) { /* A controller blocked by the engine monitor. */ }
            });
            assertTrue(filter.snapshot().completedRequestsBlockedNanos() > 0);
            // ThreadMXBean rounds cumulative blocked time to milliseconds, unlike nanoTime.
            assertTrue(filter.snapshot().reads().p95Nanos() + 1_000_000 >= filter.snapshot().completedRequestsBlockedNanos());
        } finally {
            release.countDown(); holder.join(2000); releaser.join(2000);
        }
    }
    @Test void percentilesUseNearestRankAndBoundedRecentWindow() {
        var window = new HttpPerformanceFilter.Window(100);
        assertNull(window.snapshot().p95Nanos());
        assertEquals(0, window.snapshot().samples());
        for (int i = 1; i <= 100; i++) window.add(i);
        assertEquals(95L, window.snapshot().p95Nanos());
        assertEquals(99L, window.snapshot().p99Nanos());
        for (int i = 1; i <= 100; i++) window.add(200);
        assertEquals(100, window.snapshot().samples());
        assertEquals(200L, window.snapshot().p95Nanos());
        assertEquals(200L, window.snapshot().p99Nanos());
        window.clear();
        assertEquals(0, window.snapshot().samples());
    }
    @Test void resetRejectsRequestsFromPreviousGenerationAndSeparatesCommandsFromReads() {
        var filter = new HttpPerformanceFilter(null);
        filter.record(0, true, 100, 10, 12);
        filter.record(0, false, 200, 12, 15);
        var result = filter.snapshot();
        assertEquals(100L, result.reads().p95Nanos());
        assertEquals(200L, result.commands().p99Nanos());
        assertEquals(5_000_000L, result.completedRequestsBlockedNanos());
        filter.reset();
        filter.record(0, true, 999, 0, 999);
        assertEquals(0, filter.snapshot().reads().samples());
        assertNull(filter.snapshot().completedRequestsBlockedNanos());
        filter.record(1, true, 20, -1, -1);
        assertEquals(20L, filter.snapshot().reads().p95Nanos());
        assertNull(filter.snapshot().completedRequestsBlockedNanos());
    }
}
