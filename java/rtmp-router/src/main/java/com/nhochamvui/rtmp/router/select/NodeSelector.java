package com.nhochamvui.rtmp.router.select;

import com.nhochamvui.rtmp.router.model.IngestNode;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class NodeSelector {

    private static final Comparator<IngestNode> LEAST_LOADED =
            Comparator.comparingInt(IngestNode::activeStreams)
                    .thenComparingDouble(IngestNode::cpuLoad)
                    .thenComparingDouble(IngestNode::memPct);

    private final int maxStreamsPerNode;
    private final long heartbeatStaleMs;

    public NodeSelector(int maxStreamsPerNode, long heartbeatStaleMs) {
        this.maxStreamsPerNode = maxStreamsPerNode;
        this.heartbeatStaleMs = heartbeatStaleMs;
    }

    public Optional<IngestNode> select(List<IngestNode> nodes, long nowMillis) {
        return nodes.stream()
                .filter(this::eligible)
                .filter(node -> nowMillis - node.lastHeartbeatAt() <= heartbeatStaleMs)
                .min(LEAST_LOADED);
    }

    public boolean eligible(IngestNode node) {
        return "ACTIVE".equals(node.status())
                && node.host() != null && !node.host().isBlank()
                && node.activeStreams() < maxStreamsPerNode;
    }

    public int maxStreamsPerNode() {
        return maxStreamsPerNode;
    }
}
