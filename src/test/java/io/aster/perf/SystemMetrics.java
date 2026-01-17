package io.aster.perf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JVM 内存与 GC 抽样工具，供性能基线测试复用。
 */
public final class SystemMetrics {

    private SystemMetrics() {
    }

    public static MemorySnapshot captureMemory() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();
        MemoryUsage nonHeap = bean.getNonHeapMemoryUsage();
        return new MemorySnapshot(heap.getUsed(), nonHeap.getUsed());
    }

    public static List<GcSnapshot> captureGcSnapshots() {
        return ManagementFactory.getGarbageCollectorMXBeans()
            .stream()
            .map(bean -> new GcSnapshot(bean.getName(), bean.getCollectionCount(), bean.getCollectionTime()))
            .collect(Collectors.toList());
    }

    public static MemoryWindow window(MemorySnapshot before, MemorySnapshot after) {
        return new MemoryWindow(before, after);
    }

    public static List<GcDelta> diffGc(List<GcSnapshot> before, List<GcSnapshot> after) {
        Map<String, GcSnapshot> beforeMap = before
            .stream()
            .collect(Collectors.toMap(GcSnapshot::name, snapshot -> snapshot));
        return after
            .stream()
            .map(snapshot -> {
                GcSnapshot start = beforeMap.get(snapshot.name());
                long countDelta = snapshot.count();
                long timeDelta = snapshot.timeMs();
                if (start != null) {
                    countDelta -= start.count();
                    timeDelta -= start.timeMs();
                }
                return new GcDelta(snapshot.name(), countDelta, timeDelta);
            })
            .collect(Collectors.toList());
    }

    public record MemorySnapshot(long heapUsedBytes, long nonHeapUsedBytes) {
    }

    public record MemoryWindow(MemorySnapshot before, MemorySnapshot after) {
    }

    public record GcSnapshot(String name, long count, long timeMs) {
    }

    public record GcDelta(String name, long countDelta, long timeDeltaMs) {
    }
}
