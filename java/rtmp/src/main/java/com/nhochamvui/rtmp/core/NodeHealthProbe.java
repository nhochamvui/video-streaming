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
        double pct = memoryPctFromDir(resolveCgroupDir(cgroup2MountPoint(), selfCgroupPath()));
        if (pct >= 0) {
            return pct;
        }
        pct = memoryPctFromFiles(
                Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"),
                Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        if (pct >= 0) {
            return pct;
        }
        Runtime runtime = Runtime.getRuntime();
        long heapMax = runtime.maxMemory();
        if (heapMax <= 0) {
            return -1;
        }
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        return clamp(heapUsed * 100.0 / heapMax);
    }

    static double memoryPctFromDir(Path dir) {
        return memoryPctFromFiles(dir.resolve("memory.current"), dir.resolve("memory.max"));
    }

    static Path resolveCgroupDir(String mountPoint, String selfPath) {
        if (selfPath == null || selfPath.isEmpty() || selfPath.equals("/")) {
            return Path.of(mountPoint);
        }
        return Path.of(mountPoint).resolve(selfPath.substring(1));
    }

    private static double memoryPctFromFiles(Path currentPath, Path maxPath) {
        long current = readLong(currentPath);
        long max = readLong(maxPath);
        if (current > 0 && max > 0 && max != Long.MAX_VALUE) {
            return clamp(current * 100.0 / max);
        }
        return -1;
    }

    private static String cgroup2MountPoint() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/mountinfo"))) {
                int sep = line.indexOf(" - ");
                if (sep < 0) {
                    continue;
                }
                String fstype = line.substring(sep + 3).split(" ", 2)[0];
                if ("cgroup2".equals(fstype)) {
                    return line.substring(0, sep).split(" ")[4];
                }
            }
        } catch (IOException ignored) {
        }
        return "/sys/fs/cgroup";
    }

    private static String selfCgroupPath() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                if (line.startsWith("0::")) {
                    return line.substring(3);
                }
            }
        } catch (IOException ignored) {
        }
        return null;
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
