package com.nhochamvui.rtmp.auth

import com.nhochamvui.rtmp.session.CreateStreamSessionResponse
import com.nhochamvui.rtmp.session.IngestNode
import com.nhochamvui.rtmp.session.NodeRegistry
import com.nhochamvui.rtmp.session.NodeStatus
import com.nhochamvui.rtmp.session.StreamSessionRepository
import com.nhochamvui.rtmp.session.StreamSessionStatus
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.cookie.Cookie
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.util.Optional

@MicronautTest
class AuthControllerSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    AuthProperties properties

    void 'create stream session without cookie is rejected'() {
        expect:
        exchangeSafely(HttpRequest.POST('/api/v1/stream-sessions', ''))
                .status == HttpStatus.UNAUTHORIZED
    }

    void 'login with wrong credentials is rejected'() {
        when:
        def response = exchangeSafely(
                HttpRequest.POST('/api/v1/auth/login', '{"username":"admin","password":"wrong"}')
                        .contentType('application/json')
        )

        then:
        response.status == HttpStatus.UNAUTHORIZED
    }

    void 'login with valid credentials sets an HttpOnly session cookie'() {
        when:
        def response = exchangeSafely(
                HttpRequest.POST('/api/v1/auth/login', '{"username":"admin","password":"Admin@1234"}')
                        .contentType('application/json')
        )

        then:
        response.status == HttpStatus.OK
        response.headers.get(HttpHeaders.SET_COOKIE) != null
        response.headers.get(HttpHeaders.SET_COOKIE).contains(properties.cookieName)
        response.headers.get(HttpHeaders.SET_COOKIE).toLowerCase().contains('httponly')
    }

    void 'authenticated client can create a stream session'() {
        given:
        def cookie = loginCookie()

        when:
        def response = exchangeSafely(
                HttpRequest.POST('/api/v1/stream-sessions', '').cookie(cookie)
        )

        then:
        response.status == HttpStatus.CREATED
        response.getBody(CreateStreamSessionResponse).present
        response.getBody(CreateStreamSessionResponse).get().serverUrl() == 'rtmp://node-1/live'
        response.getBody(CreateStreamSessionResponse).get().streamKey() != null
    }

    void 'logout invalidates the session'() {
        given:
        def cookie = loginCookie()

        when:
        def logoutResponse = exchangeSafely(
                HttpRequest.POST('/api/v1/auth/logout', '').cookie(cookie)
        )
        def createResponse = exchangeSafely(
                HttpRequest.POST('/api/v1/stream-sessions', '').cookie(cookie)
        )

        then:
        logoutResponse.status == HttpStatus.OK
        createResponse.status == HttpStatus.UNAUTHORIZED
    }

    void 'me reports authentication state'() {
        expect:
        client.toBlocking().retrieve(HttpRequest.GET('/api/v1/auth/me'))
                .contains('"authenticated":false')

        and:
        client.toBlocking().retrieve(HttpRequest.GET('/api/v1/auth/me').cookie(loginCookie()))
                .contains('"authenticated":true')
    }

    private HttpResponse<?> exchangeSafely(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request)
        } catch (HttpClientResponseException e) {
            return e.response
        }
    }

    private Cookie loginCookie() {
        def response = exchangeSafely(
                HttpRequest.POST('/api/v1/auth/login', '{"username":"admin","password":"Admin@1234"}')
                        .contentType('application/json')
        )
        String setCookie = response.headers.get(HttpHeaders.SET_COOKIE)
        String value = setCookie.tokenize(';').first().tokenize('=').drop(1).join('=')
        return Cookie.of(properties.cookieName, value)
    }

    @MockBean(AuthSessionStore)
    AuthSessionStore authSessionStore() {
        return new InMemoryAuthSessionStore(new AuthProperties())
    }

    @MockBean(NodeRegistry)
    NodeRegistry nodeRegistry() {
        NodeRegistry mock = Mock(NodeRegistry)
        mock.selectLeastLoadedNode() >> Optional.of(new IngestNode('node-1', 'rtmp://node-1/live', NodeStatus.ACTIVE, 0, 0, 1, Set.of()))
        return mock
    }

    @MockBean(StreamSessionRepository)
    StreamSessionRepository streamSessionRepository() {
        StreamSessionRepository mock = Mock(StreamSessionRepository)
        mock.countByRequestedIpAndStatus(_ as String, _ as StreamSessionStatus) >> 0L
        return mock
    }
}