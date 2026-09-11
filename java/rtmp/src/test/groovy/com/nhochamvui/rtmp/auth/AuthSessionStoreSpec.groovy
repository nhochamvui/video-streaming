package com.nhochamvui.rtmp.auth

import spock.lang.Specification

class AuthSessionStoreSpec extends Specification {

    def "created session tokens are unique and valid"() {
        given:
        def store = new InMemoryAuthSessionStore(new AuthProperties())

        when:
        def token1 = store.create('admin')
        def token2 = store.create('admin')

        then:
        token1 != token2
        token1.length() == 64
        store.isValid(token1)
        store.username(token1).get() == 'admin'
    }

    def "expired session is invalid"() {
        given:
        def properties = new AuthProperties()
        properties.sessionTtlSeconds = -1
        def store = new InMemoryAuthSessionStore(properties)

        when:
        def token = store.create('admin')

        then:
        !store.isValid(token)
        store.username(token).empty
    }

    def "invalidated session is invalid"() {
        given:
        def store = new InMemoryAuthSessionStore(new AuthProperties())
        def token = store.create('admin')
        store.invalidate(token)

        expect:
        !store.isValid(token)
        store.username(token).empty
    }

    def "null, empty and unknown tokens are invalid"() {
        given:
        def store = new InMemoryAuthSessionStore(new AuthProperties())

        expect:
        !store.isValid(null)
        !store.isValid('')
        !store.isValid('does-not-exist')
    }
}