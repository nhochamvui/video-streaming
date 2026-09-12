package com.nhochamvui.rtmp.session;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface NodeRegistry {
    Optional<IngestNode> selectLeastLoadedNode();

    void heartbeat(int activeStreams, Set<String> streamNames);

    Collection<IngestNode> listActiveNodes();
}
