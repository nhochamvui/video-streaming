package com.nhochamvui.rtmp.router.select;

import com.nhochamvui.rtmp.router.model.IngestNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeSelectorTest {

    private static final long NOW = 1_000_000L;

    private final NodeSelector selector = new NodeSelector(18, 45_000L);

    private static IngestNode node(String id, String host, String status, int streams, double cpu, double mem) {
        return new IngestNode(id, host, status, streams, cpu, mem, NOW);
    }

    @Test
    void picksLeastActiveStreams() {
        List<IngestNode> nodes = List.of(
                node("a", "10.0.0.1", "ACTIVE", 5, 0.9, 50),
                node("b", "10.0.0.2", "ACTIVE", 2, 0.9, 50),
                node("c", "10.0.0.3", "ACTIVE", 8, 0.1, 50));

        assertEquals("b", selector.select(nodes, NOW).orElseThrow().serverId());
    }

    @Test
    void tieBreaksByCpuThenMemory() {
        List<IngestNode> nodes = List.of(
                node("a", "10.0.0.1", "ACTIVE", 4, 0.8, 10),
                node("b", "10.0.0.2", "ACTIVE", 4, 0.2, 70),
                node("c", "10.0.0.3", "ACTIVE", 4, 0.2, 30));

        assertEquals("c", selector.select(nodes, NOW).orElseThrow().serverId());
    }

    @Test
    void ignoresIneligibleNodes() {
        List<IngestNode> nodes = List.of(
                node("draining", "10.0.0.1", "DRAINING", 0, 0, 0),
                node("no-host", "", "ACTIVE", 0, 0, 0),
                node("full", "10.0.0.3", "ACTIVE", 18, 0, 0));

        Optional<IngestNode> selected = selector.select(nodes, NOW);
        assertTrue(selected.isEmpty());
    }

    @Test
    void ignoresStaleHeartbeat() {
        IngestNode stale = new IngestNode("a", "10.0.0.1", "ACTIVE", 0, 0, 0, NOW - 60_000);
        assertTrue(selector.select(List.of(stale), NOW).isEmpty());
    }

    @Test
    void returnsEmptyWhenNoNodes() {
        assertTrue(selector.select(List.of(), NOW).isEmpty());
    }
}
