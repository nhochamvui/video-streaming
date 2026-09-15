package com.nhochamvui.rtmp.session

import spock.lang.Specification

class NodeAddressResolverSpec extends Specification {

    def "extractHost prefers HostPrivateIPv4Address"() {
        expect:
        NodeAddressResolver.extractHost('{"HostPrivateIPv4Address":"10.0.1.5","Containers":[{"Networks":[{"IPv4Addresses":["10.0.9.9"]}]}]}') == "10.0.1.5"
    }

    def "extractHost falls back to the first network IPv4 address"() {
        expect:
        NodeAddressResolver.extractHost('{"Containers":[{"Networks":[{"IPv4Addresses":["10.0.9.9"]}]}]}') == "10.0.9.9"
    }

    def "extractHost returns null when no address is present"() {
        expect:
        NodeAddressResolver.extractHost('{"foo":"bar"}') == null
        NodeAddressResolver.extractHost(null) == null
        NodeAddressResolver.extractHost("") == null
    }

    def "configured host overrides discovery"() {
        given:
        def resolver = new NodeAddressResolver("192.168.1.10")

        expect:
        resolver.resolveUncached() == "192.168.1.10"
    }
}
