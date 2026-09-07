package com.nhochamvui.rtmp;

import com.nhochamvui.rtmp.core.ClientSession;
import com.nhochamvui.rtmp.core.NodeHealthProbe;
import com.nhochamvui.rtmp.core.Server;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Controller("/")
public class StreamController {

    private final Server server;
    private final NodeHealthProbe nodeHealthProbe;
    private String indexHtml;

    @Value("${rtmp.hls.cdn-url:}")
    String hlsCdnUrl;

    @Value("${rtmp.health.max-cpu-pct:95}")
    double maxCpuPct;

    @Value("${rtmp.health.max-mem-pct:90}")
    double maxMemPct;

    public StreamController(Server server, NodeHealthProbe nodeHealthProbe) {
        this.server = server;
        this.nodeHealthProbe = nodeHealthProbe;
    }

    @Get("/config")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hlsCdn", hlsCdnUrl);
        return result;
    }

    @Get(produces = MediaType.TEXT_HTML)
    String index() {
        return renderIndex();
    }

    @Get("/dashboard")
    @Produces(MediaType.TEXT_HTML)
    String dashboard() {
        return renderIndex();
    }

    @Get("/{playbackId}")
    @Produces(MediaType.TEXT_HTML)
    String streamPlayer(String playbackId) {
        return renderIndex();
    }

    @Get("/stream-status")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> health() {
        Set<String> names = server.getActiveStreamNames();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", names.isEmpty() ? "idle" : "streaming");
        result.put("activeStreams", names.size());
        result.put("streams", new ArrayList<>(names));
        String thumbBase = hlsCdnUrl != null && !hlsCdnUrl.isBlank()
                ? hlsCdnUrl + "/hls/" : "/hls/";
        Map<String, String> thumbnails = new LinkedHashMap<>();
        for (String name : names) {
            thumbnails.put(name, thumbBase + name + "/thumbnail.jpg");
        }
        result.put("thumbnails", thumbnails);
        return result;
    }

    @Get("/health/ready")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> healthReady() {
        double cpuPct = nodeHealthProbe.cpuUsagePct();
        double memPct = nodeHealthProbe.memoryUsagePct();
        boolean overloaded = cpuPct >= maxCpuPct || memPct >= maxMemPct;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overloaded ? "unhealthy" : "healthy");
        body.put("cpuPct", cpuPct);
        body.put("memPct", memPct);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("cpuPct", maxCpuPct);
        limits.put("memPct", maxMemPct);
        body.put("limits", limits);

        HttpStatus status = overloaded ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return HttpResponse.status(status).body(body);
    }

    @Get("/stats")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> stats() {
        Map<String, ClientSession> streams = server.getActiveStreams();
        Map<String, Object> activeStreams = new LinkedHashMap<>();
        for (Map.Entry<String, ClientSession> entry : streams.entrySet()) {
            activeStreams.put(entry.getKey(), entry.getValue().getStatistics());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeStreams", activeStreams);
        return result;
    }

    @Get("/stats/{playbackId}")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> streamStats(String playbackId) {
        ClientSession session = server.getActiveStreams().get(playbackId);
        if (session == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Stream not found");
            result.put("playbackId", playbackId);
            return result;
        }
        return session.getStatistics();
    }

    @Get("/version")
    @Produces(MediaType.TEXT_PLAIN)
    String version() {
        String v = getClass().getPackage().getImplementationVersion();
        return v != null ? v : "unknown";
    }

    private String renderIndex() {
        if (indexHtml == null) {
            InputStream resource = getClass().getResourceAsStream("/static/index.html");
            if (resource == null) {
                indexHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset="utf-8">
                            <title>RTMP Server</title>
                            <style>
                                body { font-family: monospace; padding: 2em; background: #101418; color: #e5edf3; }
                                a { color: #79c0ff; }
                            </style>
                        </head>
                        <body>
                            <h1>RTMP Server</h1>
                            <p>Frontend assets not found. Build the React app in <code>frontend/</code> and copy <code>dist/</code> into <code>src/main/resources/static</code>.</p>
                        </body>
                        </html>""";
            } else {
                try {
                    indexHtml = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    try {
                        resource.close();
                    } catch (java.io.IOException ignored) {
                    }
                }
            }
        }
        return indexHtml;
    }
}