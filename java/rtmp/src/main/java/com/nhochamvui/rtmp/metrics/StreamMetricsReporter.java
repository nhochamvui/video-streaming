package com.nhochamvui.rtmp.metrics;

import com.nhochamvui.rtmp.core.Server;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;

@Singleton
public class StreamMetricsReporter {

    private static final Logger log = LoggerFactory.getLogger(StreamMetricsReporter.class);

    private final Server server;
    private final CloudWatchClient cloudWatch;

    public StreamMetricsReporter(
            Server server,
            @Value("${aws.region:}") String region
    ) {
        this.server = server;
        this.cloudWatch = region.isBlank() ? null : CloudWatchClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Scheduled(fixedDelay = "20s", initialDelay = "10s")
    void report() {
        if (cloudWatch == null) {
            return;
        }
        try {
            int activeStreams = server.activeStreamCount();
            cloudWatch.putMetricData(PutMetricDataRequest.builder()
                    .namespace("RTMP")
                    .metricData(MetricDatum.builder()
                            .metricName("ActiveStreams")
                            .value((double) activeStreams)
                            .unit(StandardUnit.COUNT)
                            .timestamp(Instant.now())
                            .build())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to publish ActiveStreams metric: {}", e.getMessage());
        }
    }
}