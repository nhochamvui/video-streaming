package com.nhochamvui.rtmp.core

import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SegmenterClientSpec extends Specification {

    def "sends the handshake and parses progress and done lines"() {
        given:
        def server = new ServerSocket(0)
        def handshake = new CopyOnWriteArrayList<String>()
        def progress = new CopyOnWriteArrayList<String>()
        def errors = new CopyOnWriteArrayList<String>()

        Thread.startVirtualThread {
            server.accept().withCloseable { sock ->
                def reader = new BufferedReader(new InputStreamReader(sock.inputStream, StandardCharsets.UTF_8))
                handshake.add(reader.readLine())
                def out = sock.outputStream
                out.write("P fps=30 bitrate=2500k speed=1x\n".getBytes(StandardCharsets.UTF_8))
                out.write("D\n".getBytes(StandardCharsets.UTF_8))
                out.flush()
            }
        }

        when:
        def client = SegmenterClient.connect(
                "tcp:127.0.0.1:${server.localPort}".toString(),
                '{"outDir":"/tmp/hls/abc/hd"}',
                { s -> progress.add(s) },
                { s -> errors.add(s) })

        for (int i = 0; i < 100 && !client.closed; i++) {
            Thread.sleep(50)
        }
        def closed = client.closed
        client.close()
        server.close()

        then:
        handshake.size() == 1
        handshake[0].contains('"outDir":"/tmp/hls/abc/hd"')
        progress.contains('fps=30 bitrate=2500k speed=1x')
        errors.isEmpty()
        closed
    }

    def "surfaces daemon errors"() {
        given:
        def server = new ServerSocket(0)
        def errors = new CopyOnWriteArrayList<String>()

        Thread.startVirtualThread {
            server.accept().withCloseable { sock ->
                def reader = new BufferedReader(new InputStreamReader(sock.inputStream, StandardCharsets.UTF_8))
                reader.readLine()
                sock.outputStream.write("E processing tag: boom\n".getBytes(StandardCharsets.UTF_8))
                sock.outputStream.flush()
            }
        }

        when:
        def client = SegmenterClient.connect("tcp:127.0.0.1:${server.localPort}".toString(), '{"outDir":"/x"}', { _ -> }, { s -> errors.add(s) })
        for (int i = 0; i < 100 && errors.isEmpty(); i++) {
            Thread.sleep(50)
        }
        def message = client.errorMessage
        client.close()
        server.close()

        then:
        errors.contains('processing tag: boom')
        message == 'processing tag: boom'
    }
}
