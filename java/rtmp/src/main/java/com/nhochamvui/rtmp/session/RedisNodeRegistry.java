package com.nhochamvui.rtmp.session;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class RedisNodeRegistry implements NodeRegistry {
    private static final int NODE_TTL_SECONDS = 30;

    private final RedisProvider redisProvider;
    private final NodeAddressResolver nodeAddressResolver;
    private final String serverId;
    private final String endpoint;
    private final String nodeStatus;

    public RedisNodeRegistry(
            RedisProvider redisProvider,
            ServerIdentity serverIdentity,
            NodeAddressResolver nodeAddressResolver,
            @Value("${rtmp.endpoint}") String endpoint,
            @Value("${rtmp.node-status:ACTIVE}") String nodeStatus
    ) {
        this.redisProvider = redisProvider;
        this.nodeAddressResolver = nodeAddressResolver;
        this.serverId = serverIdentity.serverId();
        this.endpoint = endpoint;
        this.nodeStatus = nodeStatus;
    }

    @Override
    public Optional<IngestNode> selectLeastLoadedNode() {
        return redisProvider.withCommands(redis -> {
            List<String> allKeys = scanServerKeys(redis);
            Optional<IngestNode> selected = allKeys.stream()
                    .map(key -> toNode(redis.hgetall(key)))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(node -> node.status() == NodeStatus.ACTIVE)
                    .min(Comparator.comparingInt(IngestNode::activeStreams).thenComparingDouble(IngestNode::cpuLoad));
            return selected.or(() -> Optional.of(new IngestNode(serverId, endpoint, NodeStatus.ACTIVE, 0, cpuLoad(), System.currentTimeMillis(), Set.of())));
        });
    }

    @Override
    public void heartbeat(int activeStreams, Set<String> streamNames) {
        Map<String, String> values = new HashMap<>();
        values.put("serverId", serverId);
        values.put("endpoint", endpoint);
        values.put("host", nodeAddressResolver.resolve());
        values.put("status", nodeStatus);
        values.put("activeStreams", Integer.toString(activeStreams));
        values.put("cpuLoad", Double.toString(cpuLoad()));
        values.put("memPct", Double.toString(memPct()));
        values.put("streamNames", String.join(",", streamNames));
        values.put("lastHeartbeatAt", Long.toString(System.currentTimeMillis()));
        redisProvider.withCommands(redis -> {
            redis.hset("server:" + serverId, values);
            redis.expire("server:" + serverId, NODE_TTL_SECONDS);
            return null;
        });
    }

    @Override
    public Collection<IngestNode> listActiveNodes() {
        return redisProvider.withCommands(redis -> {
            List<String> allKeys = scanServerKeys(redis);
            List<IngestNode> nodes = allKeys.stream()
                    .map(key -> toNode(redis.hgetall(key)))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(node -> node.status() == NodeStatus.ACTIVE)
                    .collect(Collectors.toList());
            if (nodes.isEmpty()) {
                return List.of(new IngestNode(serverId, endpoint, NodeStatus.ACTIVE, 0, cpuLoad(), System.currentTimeMillis(), Set.of()));
            }
            return nodes;
        });
    }

    private Optional<IngestNode> toNode(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            String streamNamesRaw = values.getOrDefault("streamNames", "");
            Set<String> streamNames = streamNamesRaw.isEmpty() ? Set.of()
                    : Set.of(streamNamesRaw.split(","));
            return Optional.of(new IngestNode(
                    values.getOrDefault("serverId", serverId),
                    values.get("endpoint"),
                    NodeStatus.valueOf(values.getOrDefault("status", "UNHEALTHY")),
                    Integer.parseInt(values.getOrDefault("activeStreams", "0")),
                    Double.parseDouble(values.getOrDefault("cpuLoad", "0")),
                    Long.parseLong(values.getOrDefault("lastHeartbeatAt", "0")),
                    streamNames
            ));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<String> scanServerKeys(io.lettuce.core.api.sync.RedisCommands<String, String> redis) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        KeyScanCursor<String> cursor = redis.scan(ScanArgs.Builder.matches("server:*").limit(100));
        while (true) {
            keys.addAll(cursor.getKeys());
            if (cursor.isFinished()) {
                break;
            }
            cursor = redis.scan(cursor);
        }
        return keys;
    }

    private double cpuLoad() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        return os.getSystemLoadAverage();
    }

    private double memPct() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean system) {
            long total = system.getTotalMemorySize();
            if (total > 0) {
                long used = total - system.getFreeMemorySize();
                return Math.max(0, Math.min(100, used * 100.0 / total));
            }
        }
        return -1;
    }
}
