package com.nhochamvui.rtmp.session;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.support.ConnectionPoolSupport;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.util.function.Function;

@Singleton
public class RedisProvider {
    private final RedisClient client;
    private final GenericObjectPool<StatefulRedisConnection<String, String>> pool;

    public RedisProvider(@Value("${redis.uri}") String redisUri) {
        this.client = RedisClient.create(redisUri);
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(8);
        config.setMaxIdle(8);
        config.setMinIdle(1);
        config.setJmxEnabled(false);
        this.pool = ConnectionPoolSupport.createGenericObjectPool(client::connect, config, true);
    }

    public <T> T withCommands(Function<RedisCommands<String, String>, T> fn) {
        try (StatefulRedisConnection<String, String> connection = pool.borrowObject()) {
            return fn.apply(connection.sync());
        } catch (Exception e) {
            throw new IllegalStateException("Redis command failed", e);
        }
    }

    @PreDestroy
    void close() {
        pool.close();
        client.shutdown();
    }
}