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
    void tieBreaksByMemoryThenCpu() {
        List<IngestNode> nodes = List.of(
                node("a", "10.0.0.1", "ACTIVE", 4, 0.2, 30),
                node("b", "10.0.0.2", "ACTIVE", 4, 0.1, 10),
                node("c", "10.0.0.3", "ACTIVE", 4, 0.9, 10));

        // b and c have the lowest memory (10); b wins on lower cpu.
        assertEquals("b", selector.select(nodes, NOW).orElseThrow().serverId());
    }

    @Test
    void leastConnectionsPrefersLowerInFlight() {
        List<IngestNode> nodes = List.of(
                node("a", "10.0.0.1", "ACTIVE", 0, 0, 0),
                node("b", "10.0.0.2", "ACTIVE", 0, 0, 0));

        // a has one connection already open on the router; b is idle.
        assertEquals("b", selector.select(nodes, NOW, id -> "a".equals(id) ? 1 : 0).orElseThrow().serverId());
    }

    @Test
    void inFlightRaisesEffectiveLoadAboveReported() {
        List<IngestNode> nodes = List.of(
                node("a", "10.0.0.1", "ACTIVE", 1, 0, 0),
                node("b", "10.0.0.2", "ACTIVE", 0, 0, 0));

        // a reports 1 stream but the router has 3 in flight -> effective load 3 vs b's 0.
        assertEquals("b", selector.select(nodes, NOW, id -> "a".equals(id) ? 3 : 0).orElseThrow().serverId());
    }

    @Test
    void inFlightCountsTowardCap() {
        List<IngestNode> nodes = List.of(
                node("a", "10.0.0.1", "ACTIVE", 0, 0, 0),
                node("b", "10.0.0.2", "ACTIVE", 0, 0, 0));

        // a is at cap only in the router's real-time view.
        assertEquals("b", selector.select(nodes, NOW, id -> "a".equals(id) ? 18 : 0).orElseThrow().serverId());
    }

    @Test
    void returnsEmptyWhenAllAtCapViaInFlight() {
        List<IngestNode> nodes = List.of(node("a", "10.0.0.1", "ACTIVE", 0, 0, 0));

        assertTrue(selector.select(nodes, NOW, id -> 18).isEmpty());
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
