package com.nhochamvui.rtmp.session;

/**
 * Thrown when every ingest node is at {@code rtmp.limits.active-streams-per-node},
 * i.e. the cluster is temporarily out of publishing capacity.
 *
 * <p>The API surfaces this as 503 + {@code Retry-After} so clients back off and
 * retry while scale-out completes. This is deliberately distinct from
 * {@link StreamSessionLimitExceeded}, which is a per-IP client error (429).
 */
public class StreamCapacityUnavailable extends RuntimeException {
    public StreamCapacityUnavailable(String message) {
        super(message);
    }
}
