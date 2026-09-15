package com.nhochamvui.rtmp.router.admin;

import com.nhochamvui.rtmp.router.RouterConfig;
import com.nhochamvui.rtmp.router.metrics.RouterMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public final class AdminServer {

    private static final Logger log = Logger.getLogger(AdminServer.class.getName());

    private final RouterConfig config;
    private final RouterMetrics metrics;

    public AdminServer(RouterConfig config, RouterMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.adminHost(), config.adminPort()), 0);
        server.createContext("/health", exchange ->
                respond(exchange, 200, "{\"status\":\"ok\"}\n", "application/json"));
        server.createContext("/metrics", exchange ->
                respond(exchange, 200, metrics.render(), "text/plain; version=0.0.4"));
        server.start();
        log.info("rtmp-router admin listening on " + config.adminHost() + ":" + config.adminPort());
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
