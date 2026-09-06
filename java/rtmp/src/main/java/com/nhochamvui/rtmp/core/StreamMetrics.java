package com.nhochamvui.rtmp.core;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class StreamMetrics {

    private final MeterRegistry registry;
    private final Server server;

    public StreamMetrics(MeterRegistry registry, Server server) {
        this.registry = registry;
        this.server = server;
    }

    @PostConstruct
    void register() {
        Gauge.builder("rtmp.active_streams", () -> server.getActiveStreams().size())
                .description("Number of active RTMP streams")
                .register(registry);

        Gauge.builder("rtmp.audio.packets.total", this, StreamMetrics::sumAudioPackets)
                .description("Total audio packets across all active streams")
                .register(registry);

        Gauge.builder("rtmp.video.packets.total", this, StreamMetrics::sumVideoPackets)
                .description("Total video packets across all active streams")
                .register(registry);

        Gauge.builder("rtmp.audio.bytes.total", this, StreamMetrics::sumAudioBytes)
                .description("Total audio bytes across all active streams")
                .register(registry);

        Gauge.builder("rtmp.video.bytes.total", this, StreamMetrics::sumVideoBytes)
                .description("Total video bytes across all active streams")
                .register(registry);

        Gauge.builder("rtmp.dropped.packets.total", this, StreamMetrics::sumDroppedPackets)
                .description("Total dropped packets across all active streams")
                .register(registry);

        Gauge.builder("rtmp.keyframes.total", this, StreamMetrics::sumKeyframes)
                .description("Total keyframes across all active streams")
                .register(registry);

        Gauge.builder("rtmp.bytes.to.segmenter.total", this, StreamMetrics::sumBytesToSegmenter)
                .description("Total bytes piped to HLS segmenter")
                .register(registry);
    }

    private Map<String, ClientSession> streams() {
        return server.getActiveStreams();
    }

    private long sumAudioPackets() {
        return streams().values().stream().mapToLong(ClientSession::getAudioPackets).sum();
    }

    private long sumVideoPackets() {
        return streams().values().stream().mapToLong(ClientSession::getVideoPackets).sum();
    }

    private long sumAudioBytes() {
        return streams().values().stream().mapToLong(ClientSession::getAudioBytes).sum();
    }

    private long sumVideoBytes() {
        return streams().values().stream().mapToLong(ClientSession::getVideoBytes).sum();
    }

    private long sumDroppedPackets() {
        return streams().values().stream().mapToLong(ClientSession::getDroppedPackets).sum();
    }

    private long sumKeyframes() {
        return streams().values().stream().mapToLong(ClientSession::getKeyframeCount).sum();
    }

    private long sumBytesToSegmenter() {
        return streams().values().stream().mapToLong(ClientSession::getBytesToFfmpeg).sum();
    }
}
