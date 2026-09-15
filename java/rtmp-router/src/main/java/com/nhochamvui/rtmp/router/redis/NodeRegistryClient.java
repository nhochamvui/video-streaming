package com.nhochamvui.rtmp.router.redis;

import com.nhochamvui.rtmp.router.RouterConfig;
import com.nhochamvui.rtmp.router.model.IngestNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NodeRegistryClient {

    private final RouterConfig config;
    private RespClient client;

    public NodeRegistryClient(RouterConfig config) {
        this.config = config;
    }

    public List<IngestNode> fetchNodes() throws IOException {
        RespClient active = client();
        try {
            return scan(active);
        } catch (IOException e) {
            closeClient();
            throw e;
        }
    }

    private synchronized RespClient client() throws IOException {
        if (client == null) {
            client = new RespClient(config.redisHost(), config.redisPort(), config.connectTimeoutMs());
        }
        return client;
    }

    private synchronized void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<IngestNode> scan(RespClient active) throws IOException {
        List<IngestNode> nodes = new ArrayList<>();
        String cursor = "0";
        do {
            Object reply = active.command("SCAN", cursor, "MATCH", "server:*", "COUNT", "200");
            List<Object> parts = (List<Object>) reply;
            cursor = (String) parts.get(0);
            List<Object> keys = (List<Object>) parts.get(1);
            for (Object key : keys) {
                Object hash = active.command("HGETALL", (String) key);
                IngestNode node = toNode((List<Object>) hash);
                if (node != null) {
                    nodes.add(node);
                }
            }
        } while (!"0".equals(cursor));
        return nodes;
    }

    static IngestNode toNode(List<Object> flat) {
        if (flat == null || flat.isEmpty()) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            fields.put((String) flat.get(i), (String) flat.get(i + 1));
        }
        String serverId = fields.get("serverId");
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        return new IngestNode(
                serverId,
                fields.getOrDefault("host", ""),
                fields.getOrDefault("status", "UNHEALTHY"),
                (int) parseDouble(fields.get("activeStreams")),
                parseDouble(fields.get("cpuLoad")),
                parseDouble(fields.get("memPct")),
                (long) parseDouble(fields.get("lastHeartbeatAt"))
        );
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
