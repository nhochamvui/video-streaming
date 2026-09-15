package com.nhochamvui.rtmp.router;

import java.util.Arrays;
import java.util.List;

public record RouterConfig(
        String listenHost,
        int listenPort,
        int nodePort,
        String redisHost,
        int redisPort,
        int maxStreamsPerNode,
        int connectTimeoutMs,
        long heartbeatStaleMs,
        String adminHost,
        int adminPort,
        List<String> staticNodes
) {

    public static RouterConfig fromEnv() {
        String[] listen = splitHostPort(env("RTMP_ROUTER_LISTEN", "0.0.0.0:1935"));
        String[] admin = splitHostPort(env("RTMP_ROUTER_ADMIN_LISTEN", "127.0.0.1:9100"));
        String[] redis = splitHostPort(stripScheme(firstNonBlank(
                System.getenv("RTMP_ROUTER_REDIS_URI"),
                System.getenv("REDIS_URI"),
                "redis://localhost:6379")));
        return new RouterConfig(
                listen[0], Integer.parseInt(listen[1]),
                intEnv("RTMP_ROUTER_NODE_PORT", 1935),
                redis[0], Integer.parseInt(redis[1]),
                intEnv("RTMP_ROUTER_MAX_STREAMS_PER_NODE", 18),
                intEnv("RTMP_ROUTER_CONNECT_TIMEOUT_MS", 2000),
                longEnv("RTMP_ROUTER_HEARTBEAT_STALE_MS", 45_000L),
                admin[0], Integer.parseInt(admin[1]),
                readStaticNodes());
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static long longEnv(String key, long defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    static String stripScheme(String uri) {
        int scheme = uri.indexOf("://");
        String withoutScheme = scheme >= 0 ? uri.substring(scheme + 3) : uri;
        int slash = withoutScheme.indexOf('/');
        return slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
    }

    static String[] splitHostPort(String value) {
        int colon = value.lastIndexOf(':');
        if (colon < 0) {
            return new String[]{"0.0.0.0", value.trim()};
        }
        return new String[]{value.substring(0, colon).trim(), value.substring(colon + 1).trim()};
    }

    private static List<String> readStaticNodes() {
        String raw = System.getenv("RTMP_ROUTER_STATIC_NODES");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(node -> !node.isEmpty())
                .toList();
    }
}
