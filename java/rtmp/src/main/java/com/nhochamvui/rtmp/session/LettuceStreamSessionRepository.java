package com.nhochamvui.rtmp.session;

import io.lettuce.core.ScriptOutputType;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public class LettuceStreamSessionRepository implements StreamSessionRepository {
    private static final String KEY_PREFIX = "publish-session:";
    private static final String COUNT_SCRIPT = """
            local keys = redis.call('KEYS', KEYS[1])
            local count = 0
            for _, key in ipairs(keys) do
              if redis.call('HGET', key, 'requestedIp') == ARGV[1]
                 and redis.call('HGET', key, 'status') == ARGV[2] then
                count = count + 1
              end
            end
            return count
            """;
    private static final String VALIDATE_SCRIPT = """
            local key = KEYS[1]
            local serverId = ARGV[1]
            local connectionId = ARGV[2]
            local publisherIp = ARGV[3]
            local now = ARGV[4]
            local ttl = tonumber(ARGV[5])
            if redis.call('EXISTS', key) == 0 then
              return {}
            end
            local assignedServerId = redis.call('HGET', key, 'assignedServerId')
            local status = redis.call('HGET', key, 'status')
            if status == 'ACTIVE' and assignedServerId ~= serverId then
              return {}
            end
            local ownerConnectionId = redis.call('HGET', key, 'connectionId')
            if status == 'ACTIVE' and ownerConnectionId and ownerConnectionId ~= '' and ownerConnectionId ~= connectionId then
              return {}
            end
            redis.call('HSET', key,
              'assignedServerId', serverId,
              'status', 'ACTIVE',
              'publisherIp', publisherIp,
              'serverId', serverId,
              'connectionId', connectionId,
              'lastValidatedAt', now,
              'lastHeartbeatAt', now)
            redis.call('EXPIRE', key, ttl)
            return redis.call('HGETALL', key)
            """;
    private static final String HEARTBEAT_SCRIPT = """
            local key = KEYS[1]
            if redis.call('EXISTS', key) == 0 then
              return 0
            end
            if redis.call('HGET', key, 'assignedServerId') ~= ARGV[1] then
              return 0
            end
            if redis.call('HGET', key, 'connectionId') ~= ARGV[2] then
              return 0
            end
            if redis.call('HGET', key, 'status') ~= 'ACTIVE' then
              return 0
            end
            redis.call('HSET', key, 'lastHeartbeatAt', ARGV[3])
            redis.call('EXPIRE', key, tonumber(ARGV[4]))
            return 1
            """;
    private static final String DISCONNECT_SCRIPT = """
            local key = KEYS[1]
            if redis.call('EXISTS', key) == 0 then
              return 0
            end
            if redis.call('HGET', key, 'assignedServerId') ~= ARGV[1] then
              return 0
            end
            if redis.call('HGET', key, 'connectionId') ~= ARGV[2] then
              return 0
            end
            redis.call('HSET', key, 'status', 'DISCONNECTED', 'lastHeartbeatAt', ARGV[3])
            redis.call('EXPIRE', key, tonumber(ARGV[4]))
            return 1
            """;

    private final RedisProvider redisProvider;

    public LettuceStreamSessionRepository(RedisProvider redisProvider) {
        this.redisProvider = redisProvider;
    }

    @Override
    public void createPending(StreamSession session, int ttlSeconds) {
        String key = key(session.lookupKey());
        Map<String, String> values = new HashMap<>();
        values.put("playbackId", session.playbackId());
        values.put("assignedServerId", session.assignedServerId());
        values.put("serverId", "");
        values.put("status", session.status().name());
        values.put("requestedIp", nullToEmpty(session.requestedIp()));
        values.put("publisherIp", nullToEmpty(session.publisherIp()));
        values.put("deviceId", nullToEmpty(session.deviceId()));
        values.put("userAgent", nullToEmpty(session.userAgent()));
        values.put("connectionId", nullToEmpty(session.connectionId()));
        values.put("createdAt", Long.toString(session.createdAt()));
        values.put("lastValidatedAt", Long.toString(session.lastValidatedAt()));
        values.put("lastHeartbeatAt", Long.toString(session.lastHeartbeatAt()));
        redisProvider.withCommands(redis -> {
            redis.hset(key, values);
            redis.expire(key, ttlSeconds);
            return null;
        });
    }

    @Override
    public Optional<StreamSession> validatePublish(String lookupKey, String serverId, String connectionId, String publisherIp, int ttlSeconds) {
        List<String> values = redisProvider.withCommands(redis -> redis.eval(VALIDATE_SCRIPT, ScriptOutputType.MULTI, new String[]{key(lookupKey)},
                serverId, connectionId, nullToEmpty(publisherIp), Long.toString(System.currentTimeMillis()), Integer.toString(ttlSeconds)));
        return toSession(lookupKey, values);
    }

    @Override
    public boolean heartbeat(String lookupKey, String serverId, String connectionId, int ttlSeconds) {
        Long result = redisProvider.withCommands(redis -> redis.eval(HEARTBEAT_SCRIPT, ScriptOutputType.INTEGER, new String[]{key(lookupKey)},
                serverId, connectionId, Long.toString(System.currentTimeMillis()), Integer.toString(ttlSeconds)));
        return result != null && result == 1L;
    }

    @Override
    public void disconnect(String lookupKey, String serverId, String connectionId, int ttlSeconds) {
        redisProvider.withCommands(redis -> redis.eval(DISCONNECT_SCRIPT, ScriptOutputType.INTEGER, new String[]{key(lookupKey)},
                serverId, connectionId, Long.toString(System.currentTimeMillis()), Integer.toString(ttlSeconds)));
    }

    @Override
    public long countByRequestedIpAndStatus(String requestedIp, StreamSessionStatus status) {
        Long count = redisProvider.withCommands(redis -> redis.eval(COUNT_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{KEY_PREFIX + "*"}, nullToEmpty(requestedIp), status.name()));
        return count == null ? 0 : count;
    }

    private Optional<StreamSession> toSession(String lookupKey, List<String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < values.size(); i += 2) {
            map.put(values.get(i), values.get(i + 1));
        }
        return Optional.of(StreamSession.fromRedis(lookupKey, map));
    }

    private static String key(String lookupKey) {
        return KEY_PREFIX + lookupKey;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
