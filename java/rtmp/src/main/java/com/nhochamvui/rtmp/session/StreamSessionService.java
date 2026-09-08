package com.nhochamvui.rtmp.session;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.util.Optional;

@Singleton
public class StreamSessionService {
    public static final int SESSION_TTL_SECONDS = 300;

    private final StreamKeyGenerator keyGenerator;
    private final StreamKeyHasher keyHasher;
    private final StreamSessionRepository repository;
    private final NodeRegistry nodeRegistry;
    private final String playbackBaseUrl;
    private final int maxPendingPerIp;
    private final int maxActivePerIp;
    private final int maxActiveStreamsPerNode;

    public StreamSessionService(
            StreamKeyGenerator keyGenerator,
            StreamKeyHasher keyHasher,
            StreamSessionRepository repository,
            NodeRegistry nodeRegistry,
            @Value("${rtmp.playback-base-url}") String playbackBaseUrl,
            @Value("${rtmp.limits.pending-per-ip:5}") int maxPendingPerIp,
            @Value("${rtmp.limits.active-per-ip:1}") int maxActivePerIp,
            @Value("${rtmp.limits.active-streams-per-node:18}") int maxActiveStreamsPerNode
    ) {
        this.keyGenerator = keyGenerator;
        this.keyHasher = keyHasher;
        this.repository = repository;
        this.nodeRegistry = nodeRegistry;
        this.playbackBaseUrl = trimTrailingSlash(playbackBaseUrl);
        this.maxPendingPerIp = maxPendingPerIp;
        this.maxActivePerIp = maxActivePerIp;
        this.maxActiveStreamsPerNode = maxActiveStreamsPerNode;
    }

    public CreateStreamSessionResponse create(StreamSessionCreateRequest request) {
        String requestedIp = request.requestedIp() == null ? "unknown" : request.requestedIp();
        if (repository.countByRequestedIpAndStatus(requestedIp, StreamSessionStatus.PENDING) >= maxPendingPerIp) {
            throw new StreamSessionLimitExceeded("Too many pending stream sessions for this IP");
        }
        if (repository.countByRequestedIpAndStatus(requestedIp, StreamSessionStatus.ACTIVE) >= maxActivePerIp) {
            throw new StreamSessionLimitExceeded("Too many active streams for this IP");
        }

        IngestNode node = nodeRegistry.selectLeastLoadedNode()
                .orElseThrow(() -> new IllegalStateException("No healthy ingest nodes are available"));
        if (node.activeStreams() >= maxActiveStreamsPerNode) {
            throw new StreamSessionLimitExceeded("All ingest nodes are at capacity");
        }
        String publishKey = keyGenerator.generatePublishKey();
        String lookupKey = keyHasher.lookupKey(publishKey);
        String playbackId = keyGenerator.generatePlaybackId();
        long now = System.currentTimeMillis();
        StreamSession session = new StreamSession(
                lookupKey,
                playbackId,
                node.serverId(),
                StreamSessionStatus.PENDING,
                requestedIp,
                null,
                request.deviceId(),
                request.userAgent(),
                null,
                now,
                0,
                0
        );
        repository.createPending(session, SESSION_TTL_SECONDS);
        return new CreateStreamSessionResponse(
                node.endpoint(),
                publishKey,
                playbackBaseUrl + "/" + playbackId,
                SESSION_TTL_SECONDS
        );
    }

    public Optional<PublishValidation> validatePublish(String publishKey, String serverId, String connectionId, String publisherIp) {
        String lookupKey = keyHasher.lookupKey(publishKey);
        String fingerprint = keyHasher.fingerprint(publishKey);
        return repository.validatePublish(lookupKey, serverId, connectionId, publisherIp, SESSION_TTL_SECONDS)
                .map(session -> new PublishValidation(lookupKey, session.playbackId(), session.assignedServerId(), fingerprint));
    }

    public String fingerprint(String publishKey) {
        return keyHasher.fingerprint(publishKey);
    }

    public boolean heartbeat(String lookupKey, String serverId, String connectionId) {
        return repository.heartbeat(lookupKey, serverId, connectionId, SESSION_TTL_SECONDS);
    }

    public void disconnect(String lookupKey, String serverId, String connectionId) {
        repository.disconnect(lookupKey, serverId, connectionId, SESSION_TTL_SECONDS);
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
