package dev.nathan.sbaagentic.platform.internal.adapter.in.mcp;

import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Ends MCP listening streams in the phase just before graceful web shutdown. Clients hold
 * {@code GET /mcp} open for server-initiated messages, and the MCP SDK closes those streams only
 * when its server bean is destroyed, after graceful shutdown has already waited for them. Closing
 * the transport also answers new MCP requests with 503 for the rest of shutdown, as Tomcat does
 * once it pauses; tool calls already running still finish.
 *
 * <p>This is for shutdown only: the SDK cannot reopen a closed transport, so after a stop MCP
 * answers 503 until the process restarts, even if the context is started again. A listening
 * stream resumed with {@code Last-Event-ID} is not registered with its session, which the SDK cannot
 * replay, so closing the transport does not reach it; the shutdown phase timeout ends it instead.
 */
@Component
class McpStreamShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpStreamShutdown.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(1);

    private final ObjectProvider<McpServerTransportProviderBase> transports;
    private volatile boolean running;

    McpStreamShutdown(ObjectProvider<McpServerTransportProviderBase> transports) {
        this.transports = transports;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        transports.orderedStream().forEach(transport -> {
            try {
                transport.closeGracefully().block(CLOSE_TIMEOUT);
            } catch (RuntimeException ex) {
                log.warn("Could not end MCP listening streams before shutdown", ex);
            }
        });
    }

    @Override
    public boolean isRunning() {

        return running;
    }

    @Override
    public int getPhase() {

        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
    }
}
