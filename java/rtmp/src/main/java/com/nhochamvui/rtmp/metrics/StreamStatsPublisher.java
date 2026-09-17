package com.nhochamvui.rtmp.metrics;

import com.nhochamvui.rtmp.core.Server;
import com.nhochamvui.rtmp.session.ServerIdentity;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Periodically mirrors this node's local stream statistics into Redis so
 * the dashboard can aggregate streams owned by other nodes.
 */
@Singleton
public class StreamStatsPublisher {

    private static final Logger log = LoggerFactory.getLogger(StreamStatsPublisher.class);

    private final Server server;
    private final ServerIdentity serverIdentity;
    private final RedisStreamStatsStore statsStore;
    private final int ttlSeconds;

    public StreamStatsPublisher(
            Server server,
            ServerIdentity serverIdentity,
            RedisStreamStatsStore statsStore,
            @Value("${rtmp.stats.publish-ttl-seconds:30}") int ttlSeconds
    ) {
        this.server = server;
        this.serverIdentity = serverIdentity;
        this.statsStore = statsStore;
        this.ttlSeconds = ttlSeconds;
    }

    @Scheduled(fixedDelay = "10s", initialDelay = "5s")
    void publish() {
        try {
            Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
            server.getActiveStreams().forEach((name, session) -> stats.put(name, session.getStatistics()));
            statsStore.publish(serverIdentity.serverId(), stats, ttlSeconds);
        } catch (Exception e) {
            log.warn("Failed to publish stream stats: {}", e.getMessage());
        }
    }
}
