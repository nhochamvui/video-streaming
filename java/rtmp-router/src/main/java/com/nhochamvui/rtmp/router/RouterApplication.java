package com.nhochamvui.rtmp.router;

import com.nhochamvui.rtmp.router.admin.AdminServer;
import com.nhochamvui.rtmp.router.metrics.RouterMetrics;
import com.nhochamvui.rtmp.router.proxy.ProxyServer;
import com.nhochamvui.rtmp.router.redis.NodeRegistryClient;
import com.nhochamvui.rtmp.router.select.NodeSelector;

import java.util.logging.Logger;

public final class RouterApplication {

    private static final Logger log = Logger.getLogger(RouterApplication.class.getName());

    public static void main(String[] args) throws Exception {
        RouterConfig config = RouterConfig.fromEnv();
        RouterMetrics metrics = new RouterMetrics();
        new AdminServer(config, metrics).start();
        NodeRegistryClient registry = new NodeRegistryClient(config);
        NodeSelector selector = new NodeSelector(config.maxStreamsPerNode(), config.heartbeatStaleMs());
        log.info("rtmp-router starting");
        new ProxyServer(config, registry, selector, metrics).start();
    }
}
