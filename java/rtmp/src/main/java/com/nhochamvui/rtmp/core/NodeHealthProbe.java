package com.nhochamvui.rtmp.core;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public class NodeHealthProbe {

    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

    public double cpuUsagePct() {
        double load = os.getSystemLoadAverage();
        if (load < 0) {
            return -1;
        }
        double pct = load / os.getAvailableProcessors() * 100.0;
        return clamp(pct);
    }

    public double memoryUsagePct() {
        long current = readLong(Path.of("/sys/fs/cgroup/memory.current"));
        long max = readLong(Path.of("/sys/fs/cgroup/memory.max"));
        if (current > 0 && max > 0 && max != Long.MAX_VALUE) {
            return clamp(current * 100.0 / max);
        }
        current = readLong(Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"));
        max = readLong(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        if (current > 0 && max > 0 && max != Long.MAX_VALUE) {
            return clamp(current * 100.0 / max);
        }
        Runtime runtime = Runtime.getRuntime();
        long heapMax = runtime.maxMemory();
        if (heapMax <= 0) {
            return -1;
        }
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        return clamp(heapUsed * 100.0 / heapMax);
    }

    private static double clamp(double pct) {
        return Math.max(0, Math.min(100, pct));
    }

    private static long readLong(Path path) {
        try {
            String value = Files.readString(path).trim();
            if (value.equalsIgnoreCase("max")) {
                return Long.MAX_VALUE;
            }
            return Long.parseLong(value);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}