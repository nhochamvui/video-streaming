package com.nhochamvui.rtmp.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RouterConfigTest {

    @Test
    void stripsRedisSchemeAndPath() {
        assertEquals("localhost:6379", RouterConfig.stripScheme("redis://localhost:6379"));
        assertEquals("redis.internal:6380", RouterConfig.stripScheme("redis://redis.internal:6380/0"));
        assertEquals("localhost:6379", RouterConfig.stripScheme("localhost:6379"));
    }

    @Test
    void splitsHostAndPort() {
        assertArrayEquals(new String[]{"0.0.0.0", "1935"}, RouterConfig.splitHostPort("0.0.0.0:1935"));
        assertArrayEquals(new String[]{"127.0.0.1", "9100"}, RouterConfig.splitHostPort("127.0.0.1:9100"));
        assertArrayEquals(new String[]{"0.0.0.0", "1935"}, RouterConfig.splitHostPort("1935"));
    }
}
