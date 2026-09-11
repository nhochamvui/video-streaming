package com.nhochamvui.rtmp.core;

/**
 * Immutable RTMP publish-throttle settings (Phase 1 experiment).
 *
 * <p>Every delay defaults to {@code 0} (OFF) and only takes effect once the
 * node's active stream count reaches {@code minStreams}. This keeps normal/idle
 * operation bit-for-bit identical to a build without the feature.
 *
 * <p>The delays only pace a burst that is about to hit the node's capacity; they
 * do NOT create capacity. See {@code docs/scaling/phase1-rtmp-throttle.md}.
 *
 * @param minStreams active-stream count at/above which throttling engages
 * @param handshakeMs delay between S0S1 and S2 during the RTMP handshake
 * @param chunkSizeMs delay when processing the SetChunkSize control message
 * @param publishMs   delay before the publish capacity check / key validation
 */
public record RtmpThrottleConfig(int minStreams, long handshakeMs, long chunkSizeMs, long publishMs) {

    /**
     * @return true when the node is at/over {@code minStreams} and at least one
     *         stage delay is enabled.
     */
    public boolean enabled(int activeStreams) {
        return activeStreams >= minStreams
                && (handshakeMs > 0 || chunkSizeMs > 0 || publishMs > 0);
    }
}
