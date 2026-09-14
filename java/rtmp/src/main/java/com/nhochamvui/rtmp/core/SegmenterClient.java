package com.nhochamvui.rtmp.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One stream's connection to the shared hls-segmenter daemon.
 *
 * <p>Protocol: a single JSON handshake line, then raw FLV bytes; the daemon replies with
 * {@code P <progress>} lines, {@code E <error>} on failure, and {@code D} when finished.
 */
public final class SegmenterClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SegmenterClient.class);

    private final SocketChannel channel;
    private final OutputStream out;
    private final BufferedReader reader;
    private final Consumer<String> onProgress;
    private final Consumer<String> onError;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile String errorMessage;

    private SegmenterClient(SocketChannel channel, Consumer<String> onProgress, Consumer<String> onError) {
        this.channel = channel;
        this.out = new BufferedOutputStream(Channels.newOutputStream(channel), 8192);
        this.reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
        this.onProgress = onProgress;
        this.onError = onError;
    }

    public static SegmenterClient connect(String address, String handshakeJson,
                                          Consumer<String> onProgress, Consumer<String> onError) throws IOException {
        SocketChannel ch = openChannel(address);
        SegmenterClient client = new SegmenterClient(ch, onProgress, onError);
        client.out.write(handshakeJson.getBytes(StandardCharsets.UTF_8));
        client.out.write('\n');
        client.out.flush();
        client.startReader();
        return client;
    }

    private static SocketChannel openChannel(String address) throws IOException {
        if (address.startsWith("unix:")) {
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(address.substring("unix:".length())));
            return ch;
        }
        String rest = address.startsWith("tcp:") ? address.substring("tcp:".length()) : address;
        int idx = rest.lastIndexOf(':');
        String host = idx > 0 ? rest.substring(0, idx) : "127.0.0.1";
        int port = Integer.parseInt(idx > 0 ? rest.substring(idx + 1) : rest);
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress(host, port));
        return ch;
    }

    private void startReader() {
        Thread.ofVirtual().name("segmenter-client").start(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("P ")) {
                        if (onProgress != null) {
                            onProgress.accept(line.substring(2));
                        }
                    } else if (line.startsWith("E ")) {
                        errorMessage = line.substring(2);
                        if (onError != null) {
                            onError.accept(errorMessage);
                        }
                    } else if (line.equals("D")) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    log.debug("segmenter client read ended: {}", e.getMessage());
                }
            } finally {
                done.countDown();
            }
        });
    }

    public OutputStream stream() {
        return out;
    }

    /** True once the connection has ended (daemon finished, errored, or was closed). */
    public boolean isClosed() {
        return closed || done.getCount() == 0;
    }

    public String errorMessage() {
        return errorMessage;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.flush();
            channel.shutdownOutput();
        } catch (IOException ignored) {
        }
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
