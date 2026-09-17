package com.nhochamvui.rtmp.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhochamvui.rtmp.session.RedisProvider;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes each node's local per-stream statistics to Redis under
 * {@code stream-stats:<serverId>} so any node can serve a cluster-wide
 * dashboard. Entries expire via TTL, so a dead node's stats disappear.
 */
@Singleton
public class RedisStreamStatsStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamStatsStore.class);
    private static final String KEY_PREFIX = "stream-stats:";

    private final RedisProvider redisProvider;
    private final ObjectMapper objectMapper;

    public RedisStreamStatsStore(RedisProvider redisProvider, ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
    }

    public void publish(String serverId, Map<String, Map<String, Object>> statsByStream, int ttlSeconds) {
        String key = KEY_PREFIX + serverId;
        try {
            if (statsByStream.isEmpty()) {
                redisProvider.withCommands(redis -> {
                    redis.del(key);
                    return null;
                });
                return;
            }
            String json = objectMapper.writeValueAsString(statsByStream);
            redisProvider.withCommands(redis -> {
                redis.setex(key, ttlSeconds, json);
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to publish stream stats for {}: {}", serverId, e.getMessage());
        }
    }

    public Map<String, Map<String, Object>> readAll() {
        try {
            return redisProvider.withCommands(redis -> {
                Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
                for (String key : scanKeys(redis)) {
                    String json = redis.get(key);
                    if (json == null || json.isBlank()) {
                        continue;
                    }
                    try {
                        Map<String, Map<String, Object>> parsed =
                                objectMapper.readValue(json, new TypeReference<>() {
                                });
                        merged.putAll(parsed);
                    } catch (Exception e) {
                        log.warn("Skipping unreadable stream stats at {}: {}", key, e.getMessage());
                    }
                }
                return merged;
            });
        } catch (Exception e) {
            log.warn("Failed to read stream stats from redis: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<String> scanKeys(RedisCommands<String, String> redis) {
        List<String> keys = new ArrayList<>();
        KeyScanCursor<String> cursor = redis.scan(ScanArgs.Builder.matches(KEY_PREFIX + "*").limit(100));
        while (true) {
            keys.addAll(cursor.getKeys());
            if (cursor.isFinished()) {
                break;
            }
            cursor = redis.scan(cursor);
        }
        return keys;
    }
}
