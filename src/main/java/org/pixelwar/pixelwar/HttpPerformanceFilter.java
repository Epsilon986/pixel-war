package org.pixelwar.pixelwar;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.pixelwar.pixelwar.domain.PerformanceTracker;
import org.pixelwar.pixelwar.domain.Simulation;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded sliding windows of completed API requests, including controller waits and serialization. */
@Component
public final class HttpPerformanceFilter extends OncePerRequestFilter {
    private static final String EPOCH_ATTRIBUTE = HttpPerformanceFilter.class.getName() + ".epoch";
    public record Percentiles(int samples, Long p95Nanos, Long p99Nanos) {}
    public record Snapshot(Percentiles reads, Percentiles commands, Long completedRequestsBlockedNanos, int windowCapacity) {}
    private final Window reads = new Window(512), commands = new Window(512);
    private final PerformanceTracker.Probe probe = PerformanceTracker.jvmProbe();
    private final Simulation simulation;
    private long generation, blockedNanos;
    private boolean blockedSupported;
    public HttpPerformanceFilter(Simulation simulation) { this.simulation = simulation; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long epoch;
        synchronized (this) { epoch = generation; }
        long thread = Thread.currentThread().threadId(), blockedBefore = probe.blockedMillis(thread), start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            long duration = System.nanoTime() - start, blockedAfter = probe.blockedMillis(thread);
            var resetEpoch = request.getAttribute(EPOCH_ATTRIBUTE);
            Long completedAt = simulation == null ? null : simulation.trialCompletedAtNanos();
            if (completedAt == null || start <= completedAt) {
                record(resetEpoch instanceof Long value ? value : epoch, "GET".equals(request.getMethod()), duration, blockedBefore, blockedAfter);
            }
        }
    }
    synchronized void record(long epoch, boolean read, long duration, long before, long after) {
        if (epoch != generation) return;
        (read ? reads : commands).add(duration);
        if (before >= 0 && after >= before) { blockedSupported = true; blockedNanos += (after - before) * 1_000_000L; }
    }
    public synchronized void reset() {
        generation++;
        reads.clear(); commands.clear(); blockedNanos = 0; blockedSupported = false;
    }
    public synchronized void resetIncluding(HttpServletRequest request) {
        reset();
        request.setAttribute(EPOCH_ATTRIBUTE, generation);
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(reads.snapshot(), commands.snapshot(), blockedSupported ? blockedNanos : null, 512);
    }
    static final class Window {
        private final long[] durations;
        private int size, next;
        Window(int capacity) { durations = new long[capacity]; }
        void add(long nanos) { durations[next] = nanos; next = (next + 1) % durations.length; size = Math.min(size + 1, durations.length); }
        void clear() { size = next = 0; }
        Percentiles snapshot() {
            if (size == 0) return new Percentiles(0, null, null);
            var sorted = Arrays.copyOf(durations, size);
            Arrays.sort(sorted);
            return new Percentiles(size, sorted[(int) Math.ceil(size * .95) - 1], sorted[(int) Math.ceil(size * .99) - 1]);
        }
    }
}
