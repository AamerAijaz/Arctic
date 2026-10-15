package com.arctic.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs the loopback URL once the MCP Server is accepting hosts. */
@Component
final class McpServerListening {

    private static final Logger log = LoggerFactory.getLogger(McpServerListening.class);

    @EventListener
    void onListening(WebServerInitializedEvent event) {
        String address = event.getApplicationContext()
                .getEnvironment()
                .getProperty("server.address", "127.0.0.1");
        log.info("MCP Server listening on http://{}:{}/mcp", address, event.getWebServer().getPort());
    }
}
