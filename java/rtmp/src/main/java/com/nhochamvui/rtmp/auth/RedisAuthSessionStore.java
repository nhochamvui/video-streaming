package com.nhochamvui.rtmp.auth;

import com.nhochamvui.rtmp.session.RedisProvider;
import jakarta.inject.Singleton;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Redis-backed {@link AuthSessionStore}.
 *
 * <p>Auth sessions are shared state: with more than one node behind the load balancer,
 * an in-memory store would reject cookies issued by another node (HTTP 401). Tokens map
 * to {@code auth:session:<token> -> username} and expire with the configured
 * {@code rtmp.auth.session-ttl-seconds}.
 */
@Singleton
public class RedisAuthSessionStore implements AuthSessionStore {
    private static final int TOKEN_BYTES = 32;
    private static final String KEY_PREFIX = "auth:session:";

    private final SecureRandom random = new SecureRandom();
    private final RedisProvider redisProvider;
    private final AuthProperties properties;

    public RedisAuthSessionStore(RedisProvider redisProvider, AuthProperties properties) {
        this.redisProvider = redisProvider;
        this.properties = properties;
    }

    @Override
    public String create(String username) {
        String token = HexFormat.of().formatHex(randomBytes());
        int ttlSeconds = properties.getSessionTtlSeconds();
        if (ttlSeconds > 0) {
            redisProvider.withCommands(redis -> {
                redis.setex(KEY_PREFIX + token, ttlSeconds, username);
                return null;
            });
        }
        return token;
    }

    @Override
    public boolean isValid(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(redisProvider.withCommands(redis -> redis.exists(KEY_PREFIX + token) > 0));
    }

    @Override
    public Optional<String> username(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisProvider.withCommands(redis -> redis.get(KEY_PREFIX + token)));
    }

    @Override
    public void invalidate(String token) {
        if (token != null && !token.isEmpty()) {
            redisProvider.withCommands(redis -> redis.del(KEY_PREFIX + token));
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }
}
