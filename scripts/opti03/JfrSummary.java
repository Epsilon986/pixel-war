package org.pixelwar.pixelwar.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/** Exports real JFR events. Allocation weights are estimates, not exact allocation counts. */
public final class JfrSummary {
    public static void main(String[] args) throws Exception {
        var eventCounts = new TreeMap<String, Long>();
        var cpuExclusive = new TreeMap<String, Long>();
        var cpuInclusive = new TreeMap<String, Long>();
        var cpuStacks = new TreeMap<String, Long>();
        var allocationClasses = new TreeMap<String, Long>();
        var allocationStacks = new TreeMap<String, Long>();
        var positionAllocationStacks = new TreeMap<String, Long>();
        var monitors = new TreeMap<String, Long>();
        var monitorCounts = new TreeMap<String, Long>();
        long gcNanos = 0, gcPauseNanos = 0, allocationSamples = 0;
        try (var file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String type = event.getEventType().getName();
                eventCounts.merge(type, 1L, Long::sum);
                var stack = event.getStackTrace();
                if (type.equals("jdk.ExecutionSample") && stack != null && !stack.getFrames().isEmpty()) {
                    var frames = stack.getFrames();
                    cpuExclusive.merge(method(frames.getFirst()), 1L, Long::sum);
                    var seen = new HashSet<String>();
                    for (var frame : frames) if (seen.add(method(frame))) cpuInclusive.merge(method(frame), 1L, Long::sum);
                    cpuStacks.merge(frames.stream().map(JfrSummary::method).collect(java.util.stream.Collectors.joining(";")), 1L, Long::sum);
                }
                if (type.equals("jdk.ObjectAllocationSample")) {
                    allocationSamples++;
                    long weight = event.getLong("weight");
                    allocationClasses.merge(event.getClass("objectClass").getName(), weight, Long::sum);
                    if (stack != null) allocationStacks.merge(stack.getFrames().stream().map(JfrSummary::method).collect(java.util.stream.Collectors.joining(";")), weight, Long::sum);
                    if (stack != null && event.getClass("objectClass").getName().equals("org.pixelwar.pixelwar.domain.PlacementStrategy$Position"))
                        positionAllocationStacks.merge(stack.getFrames().stream().map(JfrSummary::method).collect(java.util.stream.Collectors.joining(";")), weight, Long::sum);
                }
                if (type.equals("jdk.JavaMonitorEnter")) {
                    String monitor = event.getClass("monitorClass").getName();
                    monitors.merge(monitor, event.getDuration().toNanos(), Long::sum);
                    monitorCounts.merge(monitor, 1L, Long::sum);
                }
                if (type.equals("jdk.GarbageCollection")) gcNanos += event.getDuration().toNanos();
                if (type.equals("jdk.GCPhasePause")) gcPauseNanos += event.getDuration().toNanos();
            }
        }
        Files.writeString(Path.of(args[1]), PositionExperiment.json(PositionExperiment.map(
                "recording", Path.of(args[0]).getFileName().toString(), "eventCounts", eventCounts,
                "cpuExclusiveSamples", cpuExclusive, "cpuInclusiveSamples", cpuInclusive, "cpuStacks", cpuStacks,
                "allocationSamples", allocationSamples, "allocationEstimatedBytesByClass", allocationClasses,
                "allocationEstimatedBytesByStack", allocationStacks, "monitorWaitNanos", monitors, "monitorEvents", monitorCounts,
                "positionAllocationEstimatedBytesByStack", positionAllocationStacks,
                "gcCollectionDurationNanos", gcNanos, "gcStopTheWorldPauseNanos", gcPauseNanos, "monitorThresholdMillis", 1)));
    }
    private static String method(RecordedFrame frame) { return frame.getMethod().getType().getName() + "." + frame.getMethod().getName(); }
}
