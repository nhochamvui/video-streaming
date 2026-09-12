package com.nhochamvui.rtmp.core;

import jakarta.inject.Singleton;
import com.nhochamvui.rtmp.session.NodeRegistry;
import com.nhochamvui.rtmp.session.SafePlaybackPath;
import com.nhochamvui.rtmp.session.ServerIdentity;
import com.nhochamvui.rtmp.session.StreamSessionService;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class Server {

    private static final Logger log = LoggerFactory.getLogger(Server.class);

    private final ConcurrentHashMap<String, ClientSession> streams = new ConcurrentHashMap<>();
    private final StreamSessionService streamSessionService;
    private final SafePlaybackPath safePlaybackPath;
    private final NodeRegistry nodeRegistry;
    private final String serverId;
    private final int port;
    private final String hlsBucket;
    private final String hlsRegion;
    private final String hlsCdnUrl;
    private final int maxActiveStreams;
    private final RtmpThrottleConfig throttle;

    public Server(
            StreamSessionService streamSessionService,
            SafePlaybackPath safePlaybackPath,
            NodeRegistry nodeRegistry,
            ServerIdentity serverIdentity,
            @Value("${rtmp.port:1935}") int port,
            @Value("${rtmp.hls.bucket:}") String hlsBucket,
            @Value("${rtmp.hls.region:}") String hlsRegion,
            @Value("${rtmp.hls.cdn-url:}") String hlsCdnUrl,
            @Value("${rtmp.limits.active-streams-per-node:18}") int maxActiveStreams,
            @Value("${rtmp.throttle.min-streams:18}") int throttleMinStreams,
            @Value("${rtmp.throttle.handshake-ms:0}") long throttleHandshakeMs,
            @Value("${rtmp.throttle.chunk-size-ms:0}") long throttleChunkSizeMs,
            @Value("${rtmp.throttle.publish-ms:0}") long throttlePublishMs
    ) {
        this.streamSessionService = streamSessionService;
        this.safePlaybackPath = safePlaybackPath;
        this.nodeRegistry = nodeRegistry;
        this.serverId = serverIdentity.serverId();
        this.port = port;
        this.hlsBucket = hlsBucket;
        this.hlsRegion = hlsRegion;
        this.hlsCdnUrl = hlsCdnUrl;
        this.maxActiveStreams = maxActiveStreams;
        this.throttle = new RtmpThrottleConfig(throttleMinStreams, throttleHandshakeMs, throttleChunkSizeMs, throttlePublishMs);
        log.info("RTMP Server initialized | serverId={} | port={} | hlsBucket={} | hlsRegion={} | maxActiveStreams={} | throttle={}", this.serverId, port, hlsBucket, hlsRegion, maxActiveStreams, this.throttle);
    }

    public void listen() {
        try (ServerSocket serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))) {
            serverSocket.setReuseAddress(true);
            log.info("RTMP server is listening on port {}...", port);
            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread.ofVirtual().start(() -> {
                        try (socket) {
                            new ClientSession(socket, Server.this, streamSessionService, safePlaybackPath, serverId, hlsBucket, hlsRegion, hlsCdnUrl, throttle).run();
                        } catch (Exception e) {
                            log.error("ClientSession fatal error", e);
                        }
                    });
                } catch (IOException e) {
                    log.error("Failed to accept connection: {}", e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.error("Server socket error: {}", e.getMessage(), e);
        }
    }

    boolean registerStream(String name, ClientSession session) {
        ClientSession old = streams.putIfAbsent(name, session);
        if (old != null && old != session) {
            log.warn("Reject duplicate publisher for playbackId={}", name);
            return false;
        }
        return true;
    }

    void unregisterStream(String name, ClientSession session) {
        streams.remove(name, session);
    }

    public boolean canAcceptStream() {
        return streams.size() < maxActiveStreams;
    }

    public int activeStreamCount() {
        return streams.size();
    }

    public Set<String> getActiveStreamNames() {
        return Set.copyOf(streams.keySet());
    }

    public Map<String, ClientSession> getActiveStreams() {
        return Map.copyOf(streams);
    }

    @Scheduled(fixedDelay = "5s", initialDelay = "1s")
    void heartbeatNode() {
        try {
            nodeRegistry.heartbeat(streams.size(), streams.keySet());
        } catch (Exception e) {
            log.warn("Failed to heartbeat RTMP node: {}", e.getMessage());
        }
    }
}
