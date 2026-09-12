package com.nhochamvui.rtmp.session;

import java.util.Set;

public record IngestNode(
        String serverId,
        String endpoint,
        NodeStatus status,
        int activeStreams,
        double cpuLoad,
        long lastHeartbeatAt,
        Set<String> streamNames
) {
}
