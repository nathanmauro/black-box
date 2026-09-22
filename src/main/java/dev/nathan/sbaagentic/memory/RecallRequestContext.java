package dev.nathan.sbaagentic.memory;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Bounded, caller-declared attribution for one synchronous recall; never an authenticated identity. */
public final class RecallRequestContext implements AutoCloseable {

    private static final ThreadLocal<RecallRequestContext> CURRENT = new ThreadLocal<>();
    private static final Set<String> CLIENTS = Set.of("codex", "claude", "manual", "other");
    private static final Set<String> PURPOSES = Set.of("normal", "audit", "test");

    private final RecallRequestContext previous;
    private final String requestId = UUID.randomUUID().toString();
    private final String transport;
    private final String client;
    private final String purpose;
    private final String project;

    private RecallRequestContext(String transport, String client, String purpose, String project) {
        previous = CURRENT.get();
        this.transport = Set.of("http", "mcp", "internal").contains(transport) ? transport : "internal";
        this.client = bounded(client, CLIENTS);
        this.purpose = bounded(purpose, PURPOSES);
        // Syntax alone does not authorize export: RecallTelemetry also requires a configured allowlist.
        this.project = project != null && project.matches("[a-z][a-z0-9_-]{0,31}") ? project : "unknown";
        CURRENT.set(this);
    }

    public static RecallRequestContext open(String transport, String client, String purpose, String project) {

        return new RecallRequestContext(transport, client, purpose, project);
    }

    public static RecallRequestContext current() {

        return CURRENT.get();
    }

    public String requestId() {

        return requestId;
    }

    public String transport() {

        return transport;
    }

    public String client() {

        return client;
    }

    public String purpose() {

        return purpose;
    }

    public String project() {

        return project;
    }

    private static String bounded(String value, Set<String> allowed) {
        String normalized =
                value == null || value.length() > 32 ? "" : value.strip().toLowerCase(Locale.ROOT);

        return allowed.contains(normalized) ? normalized : "unknown";
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
