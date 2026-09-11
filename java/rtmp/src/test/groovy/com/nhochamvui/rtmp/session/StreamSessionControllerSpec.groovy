package com.nhochamvui.rtmp.session

import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import spock.lang.Specification

class StreamSessionControllerSpec extends Specification {

    def "cluster at capacity returns 503 with Retry-After"() {
        given:
        def controller = new StreamSessionController(new ThrowingService(new StreamCapacityUnavailable("All ingest nodes are at capacity")))

        when:
        def response = controller.create(request())

        then:
        response.status == HttpStatus.SERVICE_UNAVAILABLE
        response.headers.get('Retry-After') == '30'
    }

    def "per-IP limit still returns 429"() {
        given:
        def controller = new StreamSessionController(new ThrowingService(new StreamSessionLimitExceeded("Too many active streams for this IP")))

        when:
        def response = controller.create(request())

        then:
        response.status == HttpStatus.TOO_MANY_REQUESTS
    }

    def "a node with a free slot creates a session"() {
        given:
        def controller = new StreamSessionController(new StubService(
                new CreateStreamSessionResponse("rtmp://node-1/live", "key", "https://example.test/stream/playback", 300)))

        when:
        def response = controller.create(request())

        then:
        response.status == HttpStatus.CREATED
    }

    private HttpRequest<?> request() {
        def req = Stub(HttpRequest)
        req.getRemoteAddress() >> null
        req.getHeaders() >> Stub(HttpHeaders)
        req
    }

    static class ThrowingService extends StreamSessionService {
        private final RuntimeException failure

        ThrowingService(RuntimeException failure) {
            super(null, null, null, null, "http://localhost", 5, 1, 18)
            this.failure = failure
        }

        @Override
        CreateStreamSessionResponse create(StreamSessionCreateRequest request) {
            throw failure
        }
    }

    static class StubService extends StreamSessionService {
        private final CreateStreamSessionResponse response

        StubService(CreateStreamSessionResponse response) {
            super(null, null, null, null, "http://localhost", 5, 1, 18)
            this.response = response
        }

        @Override
        CreateStreamSessionResponse create(StreamSessionCreateRequest request) {
            return response
        }
    }
}
