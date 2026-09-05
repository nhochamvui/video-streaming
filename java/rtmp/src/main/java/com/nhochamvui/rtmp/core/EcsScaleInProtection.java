package com.nhochamvui.rtmp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.UpdateTaskProtectionRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Singleton
public class EcsScaleInProtection {
    private static final Logger log = LoggerFactory.getLogger(EcsScaleInProtection.class);

    private final Server server;
    private final int protectionExpiryMinutes;
    private final ObjectMapper objectMapper;
    private final HttpClient metadataClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private EcsClient ecsClient;
    private String cluster;
    private String taskArn;
    private boolean onEcs;

    public EcsScaleInProtection(
            Server server,
            ObjectMapper objectMapper,
            @Value("${rtmp.ecs.task-arn:}") String configuredTaskArn,
            @Value("${rtmp.ecs.protection-expiry-minutes:120}") int protectionExpiryMinutes
    ) {
        this.server = server;
        this.objectMapper = objectMapper;
        this.protectionExpiryMinutes = protectionExpiryMinutes;
        resolveTask(configuredTaskArn);
    }

    @Scheduled(fixedDelay = "30s", initialDelay = "30s")
    void reportProtection() {
        if (!onEcs) {
            return;
        }
        try {
            int activeStreams = server.getActiveStreams().size();
            boolean enabled = activeStreams > 0;
            ecsClient.updateTaskProtection(UpdateTaskProtectionRequest.builder()
                    .cluster(cluster)
                    .tasks(taskArn)
                    .protectionEnabled(enabled)
                    .expiresInMinutes(protectionExpiryMinutes)
                    .build());
            log.debug("ECS task protection updated | enabled={} activeStreams={} task={}", enabled, activeStreams, taskArn);
        } catch (Throwable e) {
            log.warn("Failed to update ECS task protection: {}", e.getMessage());
        }
    }

    private void resolveTask(String configuredTaskArn) {
        if (configuredTaskArn != null && !configuredTaskArn.isBlank()) {
            taskArn = configuredTaskArn;
            cluster = System.getenv("ECS_CLUSTER");
            initClient();
            return;
        }
        String metadataUri = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        if (metadataUri == null || metadataUri.isBlank()) {
            log.info("Not running on ECS, scale-in protection disabled");
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(metadataUri + "/task"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = metadataClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("ECS task metadata returned HTTP {}", response.statusCode());
                return;
            }
            JsonNode node = objectMapper.readTree(response.body());
            taskArn = node.path("TaskARN").asText(null);
            cluster = node.path("Cluster").asText(null);
            if (taskArn == null || taskArn.isBlank() || cluster == null || cluster.isBlank()) {
                log.warn("ECS task metadata missing TaskARN/Cluster");
                return;
            }
            initClient();
        } catch (Exception e) {
            log.warn("Failed to resolve ECS task identity: {}", e.getMessage());
        }
    }

    private void initClient() {
        if (taskArn == null || taskArn.isBlank() || cluster == null || cluster.isBlank()) {
            return;
        }
        String region = regionFromArn(taskArn);
        if (region == null) {
            log.warn("Cannot determine region from task ARN {}", taskArn);
            return;
        }
        ecsClient = EcsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        onEcs = true;
        log.info("ECS scale-in protection enabled | task={} cluster={} region={}", taskArn, cluster, region);
    }

    private static String regionFromArn(String arn) {
        String[] parts = arn.split(":");
        return parts.length > 3 && !parts[3].isBlank() ? parts[3] : null;
    }
}