package com.nhochamvui.rtmp.router.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class RouterMetrics {

    private final AtomicLong connections = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong routed = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Map<String, LongAdder> perNode = new ConcurrentHashMap<>();

    public void connectionAccepted() {
        connections.incrementAndGet();
        active.incrementAndGet();
    }

    public void connectionClosed() {
        active.decrementAndGet();
    }

    public void routed(String serverId) {
        routed.incrementAndGet();
        perNode.computeIfAbsent(serverId, key -> new LongAdder()).increment();
    }

    public void fallback() {
        fallbacks.incrementAndGet();
    }

    public void rejected() {
        rejected.incrementAndGet();
    }

    public void error() {
        errors.incrementAndGet();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP rtmp_router_active_connections Active client connections\n");
        sb.append("# TYPE rtmp_router_active_connections gauge\n");
        sb.append("rtmp_router_active_connections ").append(active.get()).append('\n');
        counter(sb, "rtmp_router_connections_total", "Accepted client connections", connections.get());
        counter(sb, "rtmp_router_routed_total", "Connections routed to a node", routed.get());
        counter(sb, "rtmp_router_fallback_total", "Connections routed via static fallback", fallbacks.get());
        counter(sb, "rtmp_router_rejected_total", "Connections rejected (no eligible node)", rejected.get());
        counter(sb, "rtmp_router_errors_total", "Routing errors", errors.get());
        sb.append("# HELP rtmp_router_node_routed_total Connections routed per node\n");
        sb.append("# TYPE rtmp_router_node_routed_total counter\n");
        perNode.forEach((node, count) -> sb.append("rtmp_router_node_routed_total{node=\"")
                .append(node).append("\"} ").append(count.sum()).append('\n'));
        return sb.toString();
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
