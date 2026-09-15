package com.nhochamvui.rtmp.router.redis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RespClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesNestedScanReply() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    byte[] buffer = new byte[4096];
                    InputStream in = socket.getInputStream();
                    if (in.read(buffer) == -1) {
                        return;
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("*2\r\n$1\r\n0\r\n*1\r\n$9\r\nserver:ab\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                }
            });
            responder.start();

            try (RespClient client = new RespClient("127.0.0.1", server.getLocalPort(), 2000)) {
                Object reply = client.command("SCAN", "0", "MATCH", "server:*", "COUNT", "200");
                List<Object> parts = (List<Object>) reply;
                assertEquals("0", parts.get(0));
                assertEquals(List.of("server:ab"), parts.get(1));
            }
            responder.join(2000);
        }
    }

    @Test
    void surfacesRedisError() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    byte[] buffer = new byte[4096];
                    if (socket.getInputStream().read(buffer) == -1) {
                        return;
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("-ERR broken\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                }
            });
            responder.start();

            try (RespClient client = new RespClient("127.0.0.1", server.getLocalPort(), 2000)) {
                try {
                    client.command("PING");
                } catch (IOException e) {
                    assertEquals("Redis error: ERR broken", e.getMessage());
                }
            }
            responder.join(2000);
        }
    }
}
