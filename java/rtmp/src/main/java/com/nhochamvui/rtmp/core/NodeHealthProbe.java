package com.nhochamvui.rtmp.core;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public class NodeHealthProbe {

    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    private static final Logger log = LoggerFactory.getLogger(NodeHealthProbe.class);
    private volatile long lastCpuUsageUs = -1;
    private volatile long lastCpuSampleNs = -1;

    public synchronized double cpuUsagePct() {
        int cores = Runtime.getRuntime().availableProcessors();
        log.info("Available CPU Cores: {}", cores);
        double systemCpuLoad = os.getSystemLoadAverage() * 100;
        log.info("System CPU Usage: {}%", systemCpuLoad);

        long usageUs = readCpuUsageUs(cgroupDir().resolve("cpu.stat"));
        if (usageUs < 0) {
            return loadAverageCpu();
        }
        long nowNs = System.nanoTime();
        if (lastCpuUsageUs < 0) {
            lastCpuUsageUs = usageUs;
            lastCpuSampleNs = nowNs;
            log.info("CPU pct: 0.0 (cgroup first sample)");
            return 0.0;
        }
        long deltaUs = usageUs - lastCpuUsageUs;
        long elapsedNs = nowNs - lastCpuSampleNs;
        lastCpuUsageUs = usageUs;
        lastCpuSampleNs = nowNs;
        int processors = os.getAvailableProcessors();
        log.info("CPU input: usageUsDelta={}, windowNs={}, processors={}", deltaUs, elapsedNs, processors);
        double pct = cpuPctFromSamples(deltaUs, elapsedNs, processors);
        log.info("CPU pct: {} (cgroup)", pct);
        return pct;
    }

    public double memoryUsagePct() {
        Path dir = cgroupDir();
        double pct = memoryPctFromDir(dir);
        if (pct >= 0) {
            log.info("Memory pct: {} (cgroup v2 {})", pct, dir);
            return pct;
        }
        pct = memoryPctFromFiles(
                Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"),
                Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        if (pct >= 0) {
            log.info("Memory pct: {} (cgroup v1)", pct);
            return pct;
        }
        Runtime runtime = Runtime.getRuntime();
        long heapMax = runtime.maxMemory();
        if (heapMax <= 0) {
            return -1;
        }
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        log.info("Memory input: heapUsed={}, heapMax={}", heapUsed, heapMax);
        double heapPct = clamp(heapUsed * 100.0 / heapMax);
        log.info("Memory pct: {} (JVM heap)", heapPct);
        return heapPct;
    }

    private static Path cgroupDir() {
        return resolveCgroupDir(cgroup2MountPoint(), selfCgroupPath());
    }

    private double loadAverageCpu() {
        double load = os.getSystemLoadAverage();
        int processors = os.getAvailableProcessors();
        log.info("CPU input: loadAverage={}, processors={}", load, processors);
        if (load < 0) {
            return -1;
        }
        double pct = clamp(load / processors * 100.0);
        log.info("CPU pct: {} (load average)", pct);
        return pct;
    }

    static double memoryPctFromDir(Path dir) {
        return memoryPctFromFiles(dir.resolve("memory.current"), dir.resolve("memory.max"));
    }

    static double cpuPctFromSamples(long deltaUs, long elapsedNs, int processors) {
        if (deltaUs <= 0 || elapsedNs <= 0 || processors <= 0) {
            return 0.0;
        }
        double cpuFraction = (double) deltaUs * 1_000.0 / elapsedNs;
        return clamp(cpuFraction / processors * 100.0);
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
        log.info("Memory input: current={}, max={} ({})", current, max, currentPath.getParent());
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

    private static long readCpuUsageUs(Path cpuStatPath) {
        try {
            for (String line : Files.readAllLines(cpuStatPath)) {
                if (line.startsWith("usage_usec")) {
                    return Long.parseLong(line.substring(line.indexOf(' ') + 1).trim());
                }
            }
        } catch (IOException | NumberFormatException e) {
        }
        return -1;
    }
}
