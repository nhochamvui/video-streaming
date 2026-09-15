package com.nhochamvui.rtmp.router.model;

public record IngestNode(
        String serverId,
        String host,
        String status,
        int activeStreams,
        double cpuLoad,
        double memPct,
        long lastHeartbeatAt
) {
}
