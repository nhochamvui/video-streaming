package com.nhochamvui.rtmp.core

import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class StreamMetricsSpec extends Specification {

    @Inject
    MeterRegistry registry

    void 'rtmp active stream gauge is registered so it is exported on /prometheus'() {
        expect:
        registry.find('rtmp.active_streams').gauge() != null
    }
}
