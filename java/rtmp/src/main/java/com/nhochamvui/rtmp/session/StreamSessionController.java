package com.nhochamvui.rtmp.session;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;

import java.util.Map;

@Controller("/api/v1/stream-sessions")
public class StreamSessionController {

    private static final int CAPACITY_RETRY_AFTER_SECONDS = 30;

    private final StreamSessionService streamSessionService;

    public StreamSessionController(StreamSessionService streamSessionService) {
        this.streamSessionService = streamSessionService;
    }

    @Post
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> create(HttpRequest<?> request) {
        try {
            StreamSessionCreateRequest createRequest = new StreamSessionCreateRequest(
                    clientIp(request),
                    request.getHeaders().get(HttpHeaders.USER_AGENT),
                    request.getHeaders().get("X-Device-Id")
            );
            return HttpResponse.created(streamSessionService.create(createRequest));
        } catch (StreamSessionLimitExceeded e) {
            return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
        } catch (StreamCapacityUnavailable e) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", Integer.toString(CAPACITY_RETRY_AFTER_SECONDS))
                    .body(Map.of("error", e.getMessage(), "retryAfterSeconds", CAPACITY_RETRY_AFTER_SECONDS));
        } catch (IllegalStateException e) {
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }

    private String clientIp(HttpRequest<?> request) {
        if (request.getRemoteAddress() == null || request.getRemoteAddress().getAddress() == null) {
            return "unknown";
        }
        return request.getRemoteAddress().getAddress().getHostAddress();
    }
}
