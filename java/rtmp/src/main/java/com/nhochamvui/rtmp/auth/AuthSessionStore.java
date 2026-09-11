package com.nhochamvui.rtmp.auth;

import java.util.Optional;

/**
 * Stores UI/API login sessions.
 *
 * <p>The production implementation ({@link RedisAuthSessionStore}) keeps sessions in
 * Redis so a cookie issued by one node is valid on every node. An in-memory
 * implementation ({@link InMemoryAuthSessionStore}) exists for tests.
 */
public interface AuthSessionStore {

    String create(String username);

    boolean isValid(String token);

    Optional<String> username(String token);

    void invalidate(String token);
}
