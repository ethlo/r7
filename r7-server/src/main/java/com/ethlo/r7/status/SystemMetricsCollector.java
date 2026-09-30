package com.ethlo.r7.status;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.List;

import com.ethlo.r7.status.dto.MemoryDto;
import com.sun.management.UnixOperatingSystemMXBean;

public final class SystemMetricsCollector
{
    private SystemMetricsCollector()
    {
    }

    public static MemoryDto collect()
    {
        final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        final MemoryUsage heap = memBean.getHeapMemoryUsage();

        // 1. Heap
        final long heapUsed = heap.getUsed();
        final long heapMax = heap.getMax();

        // 2. Direct Memory (NIO Buffers)
        long directUsed = 0;
        long directMax = 0;
        final List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (final BufferPoolMXBean pool : pools)
        {
            if ("direct".equals(pool.getName()))
            {
                directUsed = pool.getMemoryUsed();
                directMax = pool.getTotalCapacity(); // Max capacity isn't strictly enforced by default, but useful
            }
        }

        // 3. GC Pressure
        long totalGcTimeMs = 0;
        final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < gcBeans.size(); i++)
        {
            final long gcTime = gcBeans.get(i).getCollectionTime();
            if (gcTime > 0)
            {
                totalGcTimeMs += gcTime;
            }
        }

        // 4. File Descriptors (Requires cast to Sun specific bean on Unix/Linux)
        long openFds = 0;
        long maxFds = 0;
        final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof UnixOperatingSystemMXBean)
        {
            final UnixOperatingSystemMXBean unixOs = (UnixOperatingSystemMXBean) osBean;
            openFds = unixOs.getOpenFileDescriptorCount();
            maxFds = unixOs.getMaxFileDescriptorCount();
        }

        return new MemoryDto(
                heapUsed, heapMax,
                directUsed, directMax,
                totalGcTimeMs,
                openFds, maxFds
        );
    }
}