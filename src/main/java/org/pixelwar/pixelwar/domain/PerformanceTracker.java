package org.pixelwar.pixelwar.domain;

import java.lang.management.ManagementFactory;

/** Engine resources only. Samples at boundaries and every 100 ms, never per placement. */
public final class PerformanceTracker {
    public interface Probe {
        long cpuNanos(long threadId);
        long allocatedBytes(long threadId);
        long blockedMillis(long threadId);
        long heapBytes();
    }
    public record Snapshot(long actions, long attempts, long elapsedNanos, double attemptsPerSecond,
                           Long cpuNanos, Double allocatedBytesPerAction, long peakObservedHeapBytes,
                           Long engineBlockedNanos) {}
    private final Probe probe;
    private long threadId = -1, startedAt = -1, lastSampleAt, peakHeap;
    private long cpu, allocated, blocked, previousCpu = -1, previousAllocated = -1, previousBlocked = -1;
    private boolean active, cpuSupported, allocationSupported, contentionSupported;

    public PerformanceTracker(Probe probe) { this.probe = probe; reset(); }

    public void reset() {
        threadId = startedAt = -1;
        active = cpuSupported = allocationSupported = contentionSupported = false;
        cpu = allocated = blocked = lastSampleAt = 0;
        previousCpu = previousAllocated = previousBlocked = -1;
        peakHeap = probe.heapBytes();
    }
    public void beginAction(long elapsedNanos) {
        long current = Thread.currentThread().threadId();
        if (startedAt < 0) startedAt = elapsedNanos;
        if (!active || current != threadId) {
            if (active) sample(elapsedNanos);
            threadId = current;
            previousCpu = probe.cpuNanos(threadId);
            previousAllocated = probe.allocatedBytes(threadId);
            previousBlocked = probe.blockedMillis(threadId);
            cpuSupported = previousCpu >= 0;
            allocationSupported = previousAllocated >= 0;
            contentionSupported = previousBlocked >= 0;
            active = true;
        }
    }
    public void tick(long elapsedNanos) {
        if (elapsedNanos - lastSampleAt >= 100_000_000L) sample(elapsedNanos);
    }
    public void pause(long elapsedNanos) {
        if (active) sample(elapsedNanos);
        active = false;
    }
    private void sample(long elapsedNanos) {
        peakHeap = Math.max(peakHeap, probe.heapBytes());
        lastSampleAt = elapsedNanos;
        if (!active) return;
        long nextCpu = probe.cpuNanos(threadId), nextAllocated = probe.allocatedBytes(threadId), nextBlocked = probe.blockedMillis(threadId);
        if (previousCpu >= 0 && nextCpu >= previousCpu) cpu += nextCpu - previousCpu;
        if (previousAllocated >= 0 && nextAllocated >= previousAllocated) allocated += nextAllocated - previousAllocated;
        if (previousBlocked >= 0 && nextBlocked >= previousBlocked) blocked += nextBlocked - previousBlocked;
        cpuSupported &= nextCpu >= 0;
        allocationSupported &= nextAllocated >= 0;
        contentionSupported &= nextBlocked >= 0;
        previousCpu = nextCpu; previousAllocated = nextAllocated; previousBlocked = nextBlocked;
    }
    public Snapshot snapshot(long elapsedNanos, long actions, long attempts) {
        if (active) sample(elapsedNanos);
        long duration = startedAt < 0 ? 0 : Math.max(0, elapsedNanos - startedAt);
        return new Snapshot(actions, attempts, duration, duration == 0 ? 0 : attempts * 1e9 / duration,
                actions == 0 || !cpuSupported ? null : cpu,
                actions == 0 || !allocationSupported ? null : allocated / (double) actions, peakHeap,
                actions == 0 || !contentionSupported ? null : blocked * 1_000_000L);
    }
    public static Probe jvmProbe() { return JvmProbe.INSTANCE; }
    private static final class JvmProbe implements Probe {
        static final JvmProbe INSTANCE = new JvmProbe();
        private final java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        private final com.sun.management.ThreadMXBean extended = threads instanceof com.sun.management.ThreadMXBean bean ? bean : null;
        private final java.lang.management.MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private JvmProbe() {
            try { if (threads.isThreadCpuTimeSupported()) threads.setThreadCpuTimeEnabled(true); } catch (UnsupportedOperationException | SecurityException ignored) {}
            try { if (threads.isThreadContentionMonitoringSupported()) threads.setThreadContentionMonitoringEnabled(true); } catch (UnsupportedOperationException | SecurityException ignored) {}
            try { if (extended != null && extended.isThreadAllocatedMemorySupported()) extended.setThreadAllocatedMemoryEnabled(true); } catch (UnsupportedOperationException | SecurityException ignored) {}
        }
        public long cpuNanos(long id) { return threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled() ? threads.getThreadCpuTime(id) : -1; }
        public long allocatedBytes(long id) { return extended != null && extended.isThreadAllocatedMemorySupported() && extended.isThreadAllocatedMemoryEnabled() ? extended.getThreadAllocatedBytes(id) : -1; }
        public long blockedMillis(long id) {
            if (!threads.isThreadContentionMonitoringSupported() || !threads.isThreadContentionMonitoringEnabled()) return -1;
            var info = threads.getThreadInfo(id);
            return info == null ? -1 : info.getBlockedTime();
        }
        public long heapBytes() { return memory.getHeapMemoryUsage().getUsed(); }
    }
}
