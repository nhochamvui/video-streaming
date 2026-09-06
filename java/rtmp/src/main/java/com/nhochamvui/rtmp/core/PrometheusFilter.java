package com.nhochamvui.rtmp.core;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.Map;

@ServerFilter("/prometheus")
public class PrometheusFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String requiredToken;

    public PrometheusFilter(@Value("${rtmp.prometheus.token:}") String requiredToken) {
        this.requiredToken = requiredToken;
    }

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filterRequest(HttpRequest<?> request) {
        if (requiredToken == null || requiredToken.isBlank()) {
            return null;
        }
        String authHeader = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String provided = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (requiredToken.equals(provided)) {
                return null;
            }
        }
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized"))
                .header(HttpHeaders.CONTENT_TYPE, "application/json");
    }
}
