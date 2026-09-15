package com.nhochamvui.rtmp.router.redis;

import com.nhochamvui.rtmp.router.model.IngestNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NodeRegistryClientTest {

    @Test
    void parsesHeartbeatHash() {
        List<Object> flat = List.of(
                "serverId", "cheap-ip-10-0-0-5",
                "endpoint", "rtmp://example:1935/live",
                "host", "10.0.0.5",
                "status", "ACTIVE",
                "activeStreams", "3",
                "cpuLoad", "1.5",
                "memPct", "42.5",
                "streamNames", "a,b",
                "lastHeartbeatAt", "1700000000000");

        IngestNode node = NodeRegistryClient.toNode(flat);

        assertEquals("cheap-ip-10-0-0-5", node.serverId());
        assertEquals("10.0.0.5", node.host());
        assertEquals("ACTIVE", node.status());
        assertEquals(3, node.activeStreams());
        assertEquals(1.5, node.cpuLoad());
        assertEquals(42.5, node.memPct());
        assertEquals(1700000000000L, node.lastHeartbeatAt());
    }

    @Test
    void defaultsMissingFields() {
        IngestNode node = NodeRegistryClient.toNode(List.of("serverId", "n1"));

        assertEquals("n1", node.serverId());
        assertEquals("", node.host());
        assertEquals("UNHEALTHY", node.status());
        assertEquals(0, node.activeStreams());
    }

    @Test
    void returnsNullWithoutServerId() {
        assertNull(NodeRegistryClient.toNode(List.of("host", "10.0.0.1")));
        assertNull(NodeRegistryClient.toNode(List.of()));
    }
}
