package dev.nathan.sbaagentic.memory.internal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.RecallRequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One allowlisted completion record. This component never receives query or recalled content. */
@Component
public class RecallTelemetry {
    private final MeterRegistry registry;
    private final Consumer<Map<String, Object>> sink;
    private final Set<String> projects;

    @Autowired
    public RecallTelemetry(
            MeterRegistry registry,
            ObjectMapper mapper,
            @Value("${sba.memory.recall.telemetry.project-aliases:}") String aliases) {
        this(
                registry,
                fields -> {
                    try {
                        LoggerFactory.getLogger(RecallTelemetry.class).info("{}", mapper.writeValueAsString(fields));
                    } catch (JsonProcessingException ignored) {
                        // Telemetry must never turn a successful recall into an error.
                    }
                },
                aliases);
    }

    RecallTelemetry(MeterRegistry registry, Consumer<Map<String, Object>> sink, String aliases) {
        this.registry = registry;
        this.sink = sink;
        this.projects = Arrays.stream(aliases.split(","))
                .map(String::strip)
                .filter(alias -> alias.matches("[a-z][a-z0-9_-]{0,31}"))
                .limit(100)
                .collect(Collectors.toUnmodifiableSet());
    }

    static RecallTelemetry noop() {

        return new RecallTelemetry(null, fields -> {}, "");
    }

    void complete(Sample sample) {
        // Independent guards: a metrics exporter failure must not discard the log, or vice versa.
        try {
            recordMetrics(sample);
        } catch (RuntimeException ignored) {
        }
        try {
            sink.accept(fields(sample));
        } catch (RuntimeException ignored) {
        }
    }

    private void recordMetrics(Sample s) {
        if (registry == null)

            return;

        Tags tags = Tags.of(
                "transport",
                s.context.transport(),
                "client",
                s.context.client(),
                "purpose",
                s.context.purpose(),
                "mode",
                s.mode,
                "outcome",
                s.outcome);
        registry.counter("blackbox.recall.requests", tags).increment();
        registry.timer("blackbox.recall.duration", tags).record(s.durationNanos, TimeUnit.NANOSECONDS);
        if (s.outcome.equals("success")) {
            registry.summary("blackbox.recall.results", "mode", s.mode).record(s.resultCount);
            if (s.resultCount == 0)
                registry.counter("blackbox.recall.empty", "mode", s.mode).increment();
        }
        String semantic =
                s.semanticCompleted ? (s.semanticContributed ? "contributed" : "completed_empty") : s.fallbackReason;
        registry.counter("blackbox.recall.semantic", "outcome", semantic).increment();
        registry.counter("blackbox.recall.gate.candidates", "disposition", "admitted")
                .increment(s.gateAdmitted);
        registry.counter("blackbox.recall.gate.candidates", "disposition", "rejected")
                .increment(s.gateRejected);
    }

    private Map<String, Object> fields(Sample s) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("event", "blackbox.recall.completed");
        f.put("schema_version", 1);
        f.put("occurred_at", Instant.now().toString());
        f.put("request_id", s.context.requestId());
        f.put("transport", s.context.transport());
        f.put("client", s.context.client());
        f.put("purpose", s.context.purpose());
        f.put("project", projects.contains(s.context.project()) ? s.context.project() : "unknown");
        f.put("scope_category", s.scopeCategory);
        f.put("outcome", s.outcome);
        f.put("mode", s.mode);
        f.put("duration_ms", millis(s.durationNanos));
        f.put("lexical_duration_ms", millis(s.lexicalNanos));
        f.put("lexical_candidates", s.lexicalCandidates);
        f.put("semantic_attempted", s.semanticAttempted);
        f.put("semantic_completed", s.semanticCompleted);
        f.put("semantic_contributed", s.semanticContributed);
        f.put("fallback_reason", s.fallbackReason);
        f.put("embedding_probe_outcome", s.embeddingProbeOutcome);
        f.put("embedding_probe_duration_ms", millis(s.embeddingProbeNanos));
        f.put("embedding_outcome", s.embeddingOutcome);
        f.put("embedding_duration_ms", millis(s.embeddingNanos));
        f.put("vector_outcome", s.vectorOutcome);
        f.put("vector_duration_ms", millis(s.vectorNanos));
        f.put("vector_fetch_outcome", s.vectorFetchOutcome);
        f.put("vector_fetch_duration_ms", millis(s.vectorFetchNanos));
        f.put("semantic_candidates", s.semanticCandidates);
        f.put("gate_admitted", s.gateAdmitted);
        f.put("gate_rejected", s.gateRejected);
        f.put("semantic_hits", s.semanticHits);
        f.put("semantic_returned", s.semanticReturned);
        f.put("relevance_floor", Double.isFinite(s.relevanceFloor) ? s.relevanceFloor : null);
        f.put("result_count", s.resultCount);
        f.put("result_scope", "service");
        f.put("no_results", s.outcome.equals("success") && s.resultCount == 0);
        f.put("error_category", s.errorCategory);

        return f;
    }

    private static double millis(long nanos) {

        return nanos / 1_000_000.0;
    }

    static final class Sample {
        final RecallRequestContext context;
        final String scopeCategory;
        final long started = System.nanoTime();
        long durationNanos, lexicalNanos, embeddingProbeNanos, embeddingNanos, vectorNanos, vectorFetchNanos;
        int lexicalCandidates,
                semanticCandidates,
                gateAdmitted,
                gateRejected,
                semanticHits,
                semanticReturned,
                resultCount;
        double relevanceFloor;
        boolean semanticAttempted, semanticCompleted, semanticContributed;
        String outcome = "error", mode = "lexical", fallbackReason = "none", errorCategory = "none";
        String embeddingProbeOutcome = "skipped", embeddingOutcome = "skipped";
        String vectorOutcome = "skipped", vectorFetchOutcome = "skipped";

        Sample(RecallRequestContext context, String scopeCategory) {
            this.context = context;
            this.scopeCategory = scopeCategory;
        }
    }
}
