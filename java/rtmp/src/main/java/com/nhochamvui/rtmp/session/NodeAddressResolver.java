package com.nhochamvui.rtmp.session;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class NodeAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(NodeAddressResolver.class);
    private static final Pattern ECS_HOST_IP = Pattern.compile("\"HostPrivateIPv4Address\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ECS_NETWORK_IP = Pattern.compile("\"IPv4Addresses\"\\s*:\\s*\\[\\s*\"([^\"]+)\"");
    private static final int TIMEOUT_MS = 1000;

    private final String configuredHost;
    private volatile String resolvedHost;

    public NodeAddressResolver(@Value("${rtmp.advertise-host:}") String configuredHost) {
        this.configuredHost = configuredHost;
    }

    public String resolve() {
        String cached = resolvedHost;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (resolvedHost == null) {
                resolvedHost = resolveUncached();
                log.info("Resolved node advertise host: {}", resolvedHost);
            }
            return resolvedHost;
        }
    }

    String resolveUncached() {
        if (configuredHost != null && !configuredHost.isBlank()) {
            return configuredHost.trim();
        }
        String ecs = fromEcsMetadata();
        if (ecs != null) {
            return ecs;
        }
        String imds = fromImds();
        if (imds != null) {
            return imds;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("Unable to resolve node host address: {}", e.getMessage());
            return "127.0.0.1";
        }
    }

    private String fromEcsMetadata() {
        String base = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        if (base == null || base.isBlank()) {
            return null;
        }
        return extractHost(request("GET", base + "/task", null, null));
    }

    static String extractHost(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Matcher host = ECS_HOST_IP.matcher(json);
        if (host.find()) {
            return host.group(1);
        }
        Matcher network = ECS_NETWORK_IP.matcher(json);
        if (network.find()) {
            return network.group(1);
        }
        return null;
    }

    private String fromImds() {
        String token = request("PUT", "http://169.254.169.254/latest/api/token",
                "X-aws-ec2-metadata-token-ttl-seconds", "60");
        if (token == null) {
            return null;
        }
        String ip = request("GET", "http://169.254.169.254/latest/meta-data/local-ipv4",
                "X-aws-ec2-metadata-token", token.trim());
        if (ip == null) {
            return null;
        }
        String trimmed = ip.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String request(String method, String url, String headerName, String headerValue) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (headerName != null) {
                connection.setRequestProperty(headerName, headerValue);
            }
            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
