package com.nhochamvui.rtmp.router.proxy;

import com.nhochamvui.rtmp.router.RouterConfig;
import com.nhochamvui.rtmp.router.metrics.RouterMetrics;
import com.nhochamvui.rtmp.router.model.IngestNode;
import com.nhochamvui.rtmp.router.redis.NodeRegistryClient;
import com.nhochamvui.rtmp.router.select.NodeSelector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProxyServer {

    private static final Logger log = Logger.getLogger(ProxyServer.class.getName());

    private final RouterConfig config;
    private final NodeRegistryClient registry;
    private final NodeSelector selector;
    private final RouterMetrics metrics;
    private final AtomicInteger staticRoundRobin = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public ProxyServer(RouterConfig config, NodeRegistryClient registry, NodeSelector selector, RouterMetrics metrics) {
        this.config = config;
        this.registry = registry;
        this.selector = selector;
        this.metrics = metrics;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.listenHost(), config.listenPort()), 128);
            log.info("rtmp-router listening on " + config.listenHost() + ":" + config.listenPort()
                    + ", node port " + config.nodePort());
            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    Thread.ofVirtual().name("router-conn").start(() -> handle(client));
                } catch (IOException e) {
                    log.log(Level.WARNING, "accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket client) {
        metrics.connectionAccepted();
        String countedNode = null;
        try {
            client.setTcpNoDelay(true);
            Optional<IngestNode> selected = selectNode();
            if (selected.isEmpty()) {
                metrics.rejected();
                log.warning("no eligible node for " + client.getRemoteSocketAddress() + ", closing");
                closeQuietly(client);
                return;
            }
            IngestNode node = selected.get();
            Socket upstream = new Socket();
            upstream.connect(new InetSocketAddress(node.host(), config.nodePort()), config.connectTimeoutMs());
            upstream.setTcpNoDelay(true);
            countedNode = node.serverId();
            incrementInFlight(countedNode);
            metrics.routed(node.serverId());
            log.info("route " + client.getRemoteSocketAddress() + " -> " + node.serverId() + " (" + node.host() + ")");
            relay(client, upstream);
        } catch (IOException e) {
            metrics.error();
            log.log(Level.WARNING, "connection error: " + e.getMessage());
            closeQuietly(client);
        } finally {
            if (countedNode != null) {
                decrementInFlight(countedNode);
            }
            metrics.connectionClosed();
        }
    }

    private Optional<IngestNode> selectNode() {
        try {
            List<IngestNode> nodes = registry.fetchNodes();
            return selector.select(nodes, System.currentTimeMillis(), this::inFlight);
        } catch (IOException e) {
            log.log(Level.WARNING, "redis lookup failed (" + e.getMessage() + "), using static fallback");
            metrics.fallback();
            return selectStatic();
        }
    }

    private int inFlight(String serverId) {
        AtomicInteger counter = inFlight.get(serverId);
        return counter == null ? 0 : counter.get();
    }

    private void incrementInFlight(String serverId) {
        inFlight.computeIfAbsent(serverId, key -> new AtomicInteger()).incrementAndGet();
    }

    private void decrementInFlight(String serverId) {
        AtomicInteger counter = inFlight.get(serverId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    private Optional<IngestNode> selectStatic() {
        List<String> staticNodes = config.staticNodes();
        if (staticNodes.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(staticRoundRobin.getAndIncrement(), staticNodes.size());
        String address = staticNodes.get(index);
        int colon = address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon);
        return Optional.of(new IngestNode("static-" + index, host, "ACTIVE", 0, 0, 0, System.currentTimeMillis()));
    }

    private void relay(Socket client, Socket upstream) {
        Thread toUpstream = Thread.ofVirtual().name("router-to-upstream")
                .start(() -> { pump(client, upstream); shutdownOutput(upstream); });
        Thread toClient = Thread.ofVirtual().name("router-to-client")
                .start(() -> { pump(upstream, client); shutdownOutput(client); });
        try {
            toUpstream.join();
            toClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[32 * 1024];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static void shutdownOutput(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
