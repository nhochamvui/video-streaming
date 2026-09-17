package com.nhochamvui.rtmp.router.select;

import com.nhochamvui.rtmp.router.model.IngestNode;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

public final class NodeSelector {

    private final int maxStreamsPerNode;
    private final long heartbeatStaleMs;

    public NodeSelector(int maxStreamsPerNode, long heartbeatStaleMs) {
        this.maxStreamsPerNode = maxStreamsPerNode;
        this.heartbeatStaleMs = heartbeatStaleMs;
    }

    public Optional<IngestNode> select(List<IngestNode> nodes, long nowMillis) {
        return select(nodes, nowMillis, serverId -> 0);
    }

    /**
     * Least-connections selection. The router's own per-node in-flight counter is the
     * real-time load signal; the node-reported activeStreams only raises the floor so
     * connections the router cannot see are still accounted for. Ties break on memory
     * then CPU, since RAM is the scarce resource on the target instance size.
     */
    public Optional<IngestNode> select(List<IngestNode> nodes, long nowMillis, ToIntFunction<String> inFlight) {
        return nodes.stream()
                .filter(node -> eligible(node, inFlight.applyAsInt(node.serverId())))
                .filter(node -> nowMillis - node.lastHeartbeatAt() <= heartbeatStaleMs)
                .min(comparator(inFlight));
    }

    public boolean eligible(IngestNode node) {
        return eligible(node, 0);
    }

    public boolean eligible(IngestNode node, int inFlight) {
        return "ACTIVE".equals(node.status())
                && node.host() != null && !node.host().isBlank()
                && effectiveLoad(node, inFlight) < maxStreamsPerNode;
    }

    private static int effectiveLoad(IngestNode node, int inFlight) {
        return Math.max(node.activeStreams(), inFlight);
    }

    private static Comparator<IngestNode> comparator(ToIntFunction<String> inFlight) {
        return Comparator
                .comparingInt((IngestNode node) -> effectiveLoad(node, inFlight.applyAsInt(node.serverId())))
                .thenComparingDouble(IngestNode::memPct)
                .thenComparingDouble(IngestNode::cpuLoad);
    }

    public int maxStreamsPerNode() {
        return maxStreamsPerNode;
    }
}
