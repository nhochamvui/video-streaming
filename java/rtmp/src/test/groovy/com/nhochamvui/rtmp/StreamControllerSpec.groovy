package com.nhochamvui.rtmp

import com.nhochamvui.rtmp.core.NodeHealthProbe
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class StreamControllerSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void 'health ready returns OK on low utilization'() {
        given:
        StubNodeHealthProbe.cpuPct = 10.0
        StubNodeHealthProbe.memPct = 30.0

        expect:
        exchangeSafely(HttpRequest.GET('/health/ready')).status == HttpStatus.OK
    }

    void 'health ready returns 503 when cpu crosses threshold'() {
        given:
        StubNodeHealthProbe.cpuPct = 98.0
        StubNodeHealthProbe.memPct = 10.0

        expect:
        exchangeSafely(HttpRequest.GET('/health/ready')).status == HttpStatus.SERVICE_UNAVAILABLE
    }

    void 'health ready returns 503 when memory crosses threshold'() {
        given:
        StubNodeHealthProbe.cpuPct = 20.0
        StubNodeHealthProbe.memPct = 95.0

        expect:
        exchangeSafely(HttpRequest.GET('/health/ready')).status == HttpStatus.SERVICE_UNAVAILABLE
    }

    void 'health ready returns OK when metrics unavailable'() {
        given:
        StubNodeHealthProbe.cpuPct = -1.0
        StubNodeHealthProbe.memPct = -1.0

        expect:
        exchangeSafely(HttpRequest.GET('/health/ready')).status == HttpStatus.OK
    }

    private HttpResponse<?> exchangeSafely(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request)
        } catch (HttpClientResponseException e) {
            return e.response
        }
    }

    @MockBean(NodeHealthProbe)
    NodeHealthProbe nodeHealthProbe() {
        return new StubNodeHealthProbe()
    }

    private static class StubNodeHealthProbe extends NodeHealthProbe {
        static double cpuPct
        static double memPct

        @Override
        double cpuUsagePct() {
            return cpuPct
        }

        @Override
        double memoryUsagePct() {
            return memPct
        }
    }
}