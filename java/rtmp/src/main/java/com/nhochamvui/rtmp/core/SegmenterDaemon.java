package com.nhochamvui.rtmp.core;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Owns the single, long-lived {@code hls-segmenter} daemon process for this node and hands
 * out one {@link SegmenterClient} per stream. All streams share one Go runtime instead of
 * one process per stream.
 *
 * <p>Disabled with {@code rtmp.segmenter.mode=process} (the per-stream fallback).
 */
@Singleton
public class SegmenterDaemon {

    private static final Logger log = LoggerFactory.getLogger(SegmenterDaemon.class);

    private final String mode;
    private final String command;
    private final String listen;
    private final String s3Bucket;
    private final String s3Region;

    private volatile Process process;

    public SegmenterDaemon(
            @Value("${rtmp.segmenter.mode:daemon}") String mode,
            @Value("${rtmp.segmenter.command:hls-segmenter}") String command,
            @Value("${rtmp.segmenter.listen:/tmp/hls-segmenter.sock}") String listen,
            @Value("${rtmp.hls.bucket:}") String s3Bucket,
            @Value("${rtmp.hls.region:}") String s3Region) {
        this.mode = mode;
        this.command = command;
        this.listen = normalizeListen(listen);
        this.s3Bucket = s3Bucket;
        this.s3Region = s3Region;
    }

    private static String normalizeListen(String value) {
        if (value == null || value.isBlank()) {
            return "tcp:127.0.0.1:9977";
        }
        if (value.startsWith("unix:") || value.startsWith("tcp:")) {
            return value;
        }
        // A bare path means a Unix domain socket.
        return value.contains("/") ? "unix:" + value : "tcp:" + value;
    }

    public boolean isDaemonMode() {
        return "daemon".equalsIgnoreCase(mode) || "multiplex".equalsIgnoreCase(mode);
    }

    public String address() {
        return listen;
    }

    public synchronized SegmenterClient open(String outDir, Consumer<String> onProgress, Consumer<String> onError)
            throws IOException {
        ensureRunning();
        String handshake = "{\"outDir\":" + jsonString(outDir) + "}";
        return SegmenterClient.connect(listen, handshake, onProgress, onError);
    }

    private void ensureRunning() throws IOException {
        Process p = this.process;
        if (p != null && p.isAlive()) {
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.add("--listen");
        cmd.add(listen);
        if (s3Bucket != null && !s3Bucket.isBlank()) {
            cmd.add("--s3-bucket");
            cmd.add(s3Bucket);
            if (s3Region != null && !s3Region.isBlank()) {
                cmd.add("--s3-region");
                cmd.add(s3Region);
            }
        }

        log.info("Starting hls-segmenter daemon: {} --listen {} | s3Bucket={}", command, listen, s3Bucket);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();

        IOException last = null;
        for (int i = 0; i < 50; i++) {
            try {
                probe();
                log.info("hls-segmenter daemon ready on {}", listen);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("hls-segmenter daemon did not become ready: "
                + (last != null ? last.getMessage() : "timeout"));
    }

    /** A bare connect/close to confirm the daemon is accepting connections. */
    private void probe() throws IOException {
        if (listen.startsWith("unix:")) {
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(listen.substring("unix:".length())));
            }
            return;
        }
        String rest = listen.startsWith("tcp:") ? listen.substring("tcp:".length()) : listen;
        int idx = rest.lastIndexOf(':');
        String host = idx > 0 ? rest.substring(0, idx) : "127.0.0.1";
        int port = Integer.parseInt(idx > 0 ? rest.substring(idx + 1) : rest);
        try (SocketChannel ch = SocketChannel.open()) {
            ch.connect(new InetSocketAddress(host, port));
        }
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    @PreDestroy
    void stop() {
        Process p = this.process;
        if (p != null && p.isAlive()) {
            p.destroy();
            log.info("Stopped hls-segmenter daemon");
        }
    }
}
