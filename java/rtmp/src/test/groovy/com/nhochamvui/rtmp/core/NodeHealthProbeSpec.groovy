package com.nhochamvui.rtmp.core

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class NodeHealthProbeSpec extends Specification {

    @TempDir
    Path tempDir

    def 'memory percent reads current and max from cgroup v2 files'() {
        given:
        write('memory.current', '360000000')
        write('memory.max', '671088640')

        expect:
        def pct = NodeHealthProbe.memoryPctFromDir(tempDir)
        pct > 53.6
        pct < 53.7
    }

    def 'memory percent returns -1 when limit is unlimited'() {
        given:
        write('memory.current', '1000000')
        write('memory.max', 'max')

        expect:
        NodeHealthProbe.memoryPctFromDir(tempDir) == -1.0d
    }

    def 'memory percent returns -1 when files are missing'() {
        expect:
        NodeHealthProbe.memoryPctFromDir(tempDir) == -1.0d
    }

    def 'cgroup dir resolves a nested self path'() {
        expect:
        NodeHealthProbe.resolveCgroupDir('/sys/fs/cgroup', '/ecstasks.slice/ecs-1/container') ==
                Path.of('/sys/fs/cgroup/ecstasks.slice/ecs-1/container')
    }

    def 'cgroup dir stays at the mount point for root or missing self path'() {
        expect:
        NodeHealthProbe.resolveCgroupDir('/sys/fs/cgroup', '/') == Path.of('/sys/fs/cgroup')
        NodeHealthProbe.resolveCgroupDir('/sys/fs/cgroup', null) == Path.of('/sys/fs/cgroup')
        NodeHealthProbe.resolveCgroupDir('/sys/fs/cgroup', '') == Path.of('/sys/fs/cgroup')
    }

    private void write(String name, String content) {
        Files.writeString(tempDir.resolve(name), content)
    }
}