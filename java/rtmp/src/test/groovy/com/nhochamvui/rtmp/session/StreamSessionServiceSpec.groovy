package com.nhochamvui.rtmp.session

import spock.lang.Specification

class StreamSessionServiceSpec extends Specification {

    def "create returns opaque publish key and public playback URL without exposing credential"() {
        given:
        def repo = new MemoryRepo()
        def generator = new FixedGenerator("private-publish-key", "public-playback-id")
        def hasher = new StreamKeyHasher("01234567890123456789012345678901")
        def service = new StreamSessionService(
                generator,
                hasher,
                repo,
                new FixedNodeRegistry(new IngestNode("node-1", "rtmp://node-1/live", NodeStatus.ACTIVE, 0, 0, 1, Set.of())),
                "https://example.test/stream",
                5,
                1,
                18
        )

        when:
        def response = service.create(new StreamSessionCreateRequest("203.0.113.10", "browser", null))

        then:
        response.serverUrl() == "rtmp://node-1/live"
        response.streamKey() == "private-publish-key"
        response.playbackUrl() == "https://example.test/stream/public-playback-id"
        !response.playbackUrl().contains(response.streamKey())

        and:
        repo.sessions.values().first().playbackId() == "public-playback-id"
        repo.sessions.values().first().assignedServerId() == "node-1"
        repo.sessions.values().first().status() == StreamSessionStatus.PENDING
    }

    def "active stream limit is enforced per requested IP"() {
        given:
        def repo = new MemoryRepo()
        repo.activeCount = 1
        def service = new StreamSessionService(
                new FixedGenerator("key", "playback"),
                new StreamKeyHasher("01234567890123456789012345678901"),
                repo,
                new FixedNodeRegistry(new IngestNode("node-1", "rtmp://node-1/live", NodeStatus.ACTIVE, 0, 0, 1, Set.of())),
                "https://example.test/stream",
                5,
                1,
                18
        )

        when:
        service.create(new StreamSessionCreateRequest("203.0.113.10", "browser", null))

        then:
        thrown(StreamSessionLimitExceeded)
    }

    def "session creation is rejected when the cluster is at capacity"() {
        given:
        def repo = new MemoryRepo()
        def service = new StreamSessionService(
                new FixedGenerator("key", "playback"),
                new StreamKeyHasher("01234567890123456789012345678901"),
                repo,
                new FixedNodeRegistry(new IngestNode("node-1", "rtmp://node-1/live", NodeStatus.ACTIVE, 18, 0, 1, Set.of())),
                "https://example.test/stream",
                5,
                1,
                18
        )

        when:
        service.create(new StreamSessionCreateRequest("203.0.113.10", "browser", null))

        then:
        thrown(StreamCapacityUnavailable)
    }

    def "valid publish claims session and exposes playbackId only"() {
        given:
        def repo = new MemoryRepo()
        def hasher = new StreamKeyHasher("01234567890123456789012345678901")
        def lookupKey = hasher.lookupKey("private-publish-key")
        repo.sessions[lookupKey] = new StreamSession(
                lookupKey,
                "public-playback-id",
                "node-1",
                StreamSessionStatus.PENDING,
                "203.0.113.10",
                null,
                null,
                "browser",
                null,
                1,
                0,
                0
        )
        def service = new StreamSessionService(
                new FixedGenerator("unused", "unused"),
                hasher,
                repo,
                new FixedNodeRegistry(new IngestNode("node-1", "rtmp://node-1/live", NodeStatus.ACTIVE, 0, 0, 1, Set.of())),
                "https://example.test/stream",
                5,
                1,
                18
        )

        when:
        def validation = service.validatePublish("private-publish-key", "node-1", "conn-1", "198.51.100.20")

        then:
        validation.present
        validation.get().playbackId() == "public-playback-id"
        validation.get().lookupKey() != "private-publish-key"
        repo.sessions[lookupKey].status() == StreamSessionStatus.ACTIVE
        repo.sessions[lookupKey].connectionId() == "conn-1"
        repo.sessions[lookupKey].publisherIp() == "198.51.100.20"
    }

    def "pending session can be claimed by the node reached through proxy routing"() {
        given:
        def repo = new MemoryRepo()
        def hasher = new StreamKeyHasher("01234567890123456789012345678901")
        def lookupKey = hasher.lookupKey("private-publish-key")
        repo.sessions[lookupKey] = new StreamSession(
                lookupKey,
                "public-playback-id",
                "node-selected-by-api",
                StreamSessionStatus.PENDING,
                "203.0.113.10",
                null,
                null,
                "browser",
                null,
                1,
                0,
                0
        )
        def service = new StreamSessionService(
                new FixedGenerator("unused", "unused"),
                hasher,
                repo,
                new FixedNodeRegistry(new IngestNode("node-selected-by-api", "rtmp://rtmp.example.test/live", NodeStatus.ACTIVE, 0, 0, 1, Set.of())),
                "https://example.test/stream",
                5,
                1,
                18
        )

        when:
        def validation = service.validatePublish("private-publish-key", "node-reached-by-proxy", "conn-1", "198.51.100.20")

        then:
        validation.present
        repo.sessions[lookupKey].assignedServerId() == "node-reached-by-proxy"
        repo.sessions[lookupKey].connectionId() == "conn-1"
    }


    def "duplicate publish is rejected without evicting active publisher"() {
        given:
        def repo = new MemoryRepo()
        def hasher = new StreamKeyHasher("01234567890123456789012345678901")
        def lookupKey = hasher.lookupKey("private-publish-key")
        repo.sessions[lookupKey] = new StreamSession(
                lookupKey,
                "public-playback-id",
                "node-1",
                StreamSessionStatus.ACTIVE,
                "203.0.113.10",
                "198.51.100.20",
                null,
                "browser",
                "healthy-conn",
                1,
                2,
                3
        )
        def service = new StreamSessionService(
                new FixedGenerator("unused", "unused"),
                hasher,
                repo,
                new FixedNodeRegistry(new IngestNode("node-1", "rtmp://node-1/live", NodeStatus.ACTIVE, 0, 0, 1, Set.of())),
                "https://example.test/stream",
                5,
                1,
                18
        )

        when:
        def duplicate = service.validatePublish("private-publish-key", "node-1", "copy-conn", "198.51.100.21")

        then:
        duplicate.empty
        repo.sessions[lookupKey].status() == StreamSessionStatus.ACTIVE
        repo.sessions[lookupKey].connectionId() == "healthy-conn"
        repo.sessions[lookupKey].publisherIp() == "198.51.100.20"
    }

    def "disconnect releases a session for reconnect while old heartbeat lease is invalid"() {
        given:
        def repo = new MemoryRepo()
        def hasher = new StreamKeyHasher("01234567890123456789012345678901")
        def lookupKey = hasher.lookupKey("private-publish-key")
        repo.sessions[lookupKey] = new StreamSession(
                lookupKey,
                "public-playback-id",
                "node-1",
                StreamSessionStatus.PENDING,
                "203.0.113.10",
                null,
                null,
                "browser",
                null,
                1,
                0,
                0
        )
        def service = new StreamSessionService(
                new FixedGenerator("unused", "unused"),
                hasher,
                repo,
                new FixedNodeRegistry(new IngestNode("node-1", "rtmp://node-1/live", NodeStatus.ACTIVE, 0, 0, 1, Set.of())),
                "https://example.test/stream",
                5,
                1,
                18
        )

        when:
        def firstPublish = service.validatePublish("private-publish-key", "node-1", "conn-1", "198.51.100.20")
        service.disconnect(lookupKey, "node-1", "conn-1")
        def oldHeartbeat = service.heartbeat(lookupKey, "node-1", "conn-1")
        def reconnect = service.validatePublish("private-publish-key", "node-1", "conn-2", "198.51.100.20")

        then:
        firstPublish.present
        !oldHeartbeat
        reconnect.present
        reconnect.get().playbackId() == "public-playback-id"
        repo.sessions[lookupKey].status() == StreamSessionStatus.ACTIVE
        repo.sessions[lookupKey].connectionId() == "conn-2"
    }

    def "safe playback path rejects traversal"() {
        given:
        def paths = new SafePlaybackPath("hls")

        when:
        paths.streamDirectory("../secret")

        then:
        thrown(IllegalArgumentException)
    }

    static class FixedGenerator extends StreamKeyGenerator {
        String publishKey
        String playbackId

        FixedGenerator(String publishKey, String playbackId) {
            this.publishKey = publishKey
            this.playbackId = playbackId
        }

        @Override
        String generatePublishKey() {
            publishKey
        }

        @Override
        String generatePlaybackId() {
            playbackId
        }
    }

    static class FixedNodeRegistry implements NodeRegistry {
        IngestNode node

        FixedNodeRegistry(IngestNode node) {
            this.node = node
        }

        @Override
        Optional<IngestNode> selectLeastLoadedNode() {
            Optional.of(node)
        }

        @Override
        void heartbeat(int activeStreams, Set<String> streamNames) {
        }

        @Override
        Collection<IngestNode> listActiveNodes() {
            [node]
        }
    }

    static class MemoryRepo implements StreamSessionRepository {
        Map<String, StreamSession> sessions = [:]
        long pendingCount = 0
        long activeCount = 0

        @Override
        void createPending(StreamSession session, int ttlSeconds) {
            sessions[session.lookupKey()] = session
        }

        @Override
        Optional<StreamSession> validatePublish(String lookupKey, String serverId, String connectionId, String publisherIp, int ttlSeconds) {
            def session = sessions[lookupKey]
            if (!session) {
                return Optional.empty()
            }
            if (session.status() == StreamSessionStatus.ACTIVE && session.assignedServerId() != serverId) {
                return Optional.empty()
            }
            if (session.status() == StreamSessionStatus.ACTIVE && session.connectionId() && session.connectionId() != connectionId) {
                return Optional.empty()
            }
            def active = new StreamSession(
                    lookupKey,
                    session.playbackId(),
                    serverId,
                    StreamSessionStatus.ACTIVE,
                    session.requestedIp(),
                    publisherIp,
                    session.deviceId(),
                    session.userAgent(),
                    connectionId,
                    session.createdAt(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis()
            )
            sessions[lookupKey] = active
            Optional.of(active)
        }

        @Override
        boolean heartbeat(String lookupKey, String serverId, String connectionId, int ttlSeconds) {
            def session = sessions[lookupKey]
            if (!session || session.assignedServerId() != serverId || session.connectionId() != connectionId || session.status() != StreamSessionStatus.ACTIVE) {
                return false
            }
            sessions[lookupKey] = new StreamSession(
                    lookupKey,
                    session.playbackId(),
                    session.assignedServerId(),
                    session.status(),
                    session.requestedIp(),
                    session.publisherIp(),
                    session.deviceId(),
                    session.userAgent(),
                    session.connectionId(),
                    session.createdAt(),
                    session.lastValidatedAt(),
                    System.currentTimeMillis()
            )
            true
        }

        @Override
        void disconnect(String lookupKey, String serverId, String connectionId, int ttlSeconds) {
            def session = sessions[lookupKey]
            if (session && session.assignedServerId() == serverId && session.connectionId() == connectionId) {
                sessions[lookupKey] = new StreamSession(
                        lookupKey,
                        session.playbackId(),
                        session.assignedServerId(),
                        StreamSessionStatus.DISCONNECTED,
                        session.requestedIp(),
                        session.publisherIp(),
                        session.deviceId(),
                        session.userAgent(),
                        session.connectionId(),
                        session.createdAt(),
                        session.lastValidatedAt(),
                        System.currentTimeMillis()
                )
            }
        }

        @Override
        long countByRequestedIpAndStatus(String requestedIp, StreamSessionStatus status) {
            if (status == StreamSessionStatus.PENDING) {
                return pendingCount
            }
            if (status == StreamSessionStatus.ACTIVE) {
                return activeCount
            }
            0
        }
    }
}
