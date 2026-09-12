package com.nhochamvui.rtmp.session;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
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
    private final String serverId;
    private final String endpoint;
    private final String nodeStatus;

    public RedisNodeRegistry(
            RedisProvider redisProvider,
            ServerIdentity serverIdentity,
            @Value("${rtmp.endpoint}") String endpoint,
            @Value("${rtmp.node-status:ACTIVE}") String nodeStatus
    ) {
        this.redisProvider = redisProvider;
        this.serverId = serverIdentity.serverId();
        this.endpoint = endpoint;
        this.nodeStatus = nodeStatus;
    }

    @Override
    public Optional<IngestNode> selectLeastLoadedNode() {
        return redisProvider.withCommands(redis -> {
            Optional<IngestNode> selected = redis.keys("server:*").stream()
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
        values.put("status", nodeStatus);
        values.put("activeStreams", Integer.toString(activeStreams));
        values.put("cpuLoad", Double.toString(cpuLoad()));
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
            List<IngestNode> nodes = redis.keys("server:*").stream()
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

    private double cpuLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os.getSystemLoadAverage();
    }
}
