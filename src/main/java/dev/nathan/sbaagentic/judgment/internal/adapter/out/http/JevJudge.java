package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.application.port.Judge;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgeStats;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JevJudge implements Judge {

    static final String ENDPOINT = "https://api.typesafe.ai/v1/systemone";

    private static final Logger log = LoggerFactory.getLogger(JevJudge.class);

    private final String apiKey;
    private final String endpoint;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final JevTransport transport;
    private final JevRequestBuilder requestBuilder;
    private final JevAnswerValidator validator;
    private final Clock clock;
    private final JevTelemetry telemetry;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile Long lastLatencyMs;

    public JevJudge(
            String apiKey,
            Duration timeout,
            ObjectMapper objectMapper,
            JevTransport transport,
            JudgeQuestionSet questions,
            Clock clock) {
        this(
                apiKey,
                ENDPOINT,
                timeout,
                objectMapper,
                transport,
                questions,
                clock,
                JevTelemetry.noop(),
                UnaryOperator.identity());
    }

    public JevJudge(
            String apiKey,
            Duration timeout,
            ObjectMapper objectMapper,
            JevTransport transport,
            JudgeQuestionSet questions,
            Clock clock,
            JevTelemetry telemetry,
            UnaryOperator<String> sanitize) {
        this(apiKey, ENDPOINT, timeout, objectMapper, transport, questions, clock, telemetry, sanitize);
    }

    JevJudge(
            String apiKey,
            String endpoint,
            Duration timeout,
            ObjectMapper objectMapper,
            JevTransport transport,
            JudgeQuestionSet questions,
            Clock clock,
            JevTelemetry telemetry,
            UnaryOperator<String> sanitize) {
        this.telemetry = telemetry;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.requestBuilder = new JevRequestBuilder(questions, sanitize);
        this.validator = new JevAnswerValidator(questions);
        this.clock = clock;
    }

    @Override
    public Optional<Judgment> judge(BeatState state) {
        if (apiKey == null || apiKey.isBlank()) {

            return Optional.empty();
        }
        Instant start = clock.instant();
        calls.incrementAndGet();
        String requestId = UUID.randomUUID().toString();
        try {
            String body = objectMapper.writeValueAsString(requestBuilder.build(state));
            telemetry.requested(requestId, state, clock.instant(), body, apiKey);
            String response = transport.post(endpoint, apiKey, body, timeout);
            long latency = Duration.between(start, clock.instant()).toMillis();
            lastLatencyMs = latency;
            JsonNode json = objectMapper.readTree(response);

            Judgment judgment = validator.validate(json, state, clock.instant(), latency);
            telemetry.completed(requestId, state, clock.instant(), latency, judgment, null);

            return Optional.of(judgment);
        } catch (Exception ex) {
            failures.incrementAndGet();
            long latency = Duration.between(start, clock.instant()).toMillis();
            lastLatencyMs = latency;
            telemetry.completed(requestId, state, clock.instant(), latency, null, ex);
            // Provider exception messages can contain private response content.
            log.warn("Jev judgment failed ({})", ex.getClass().getSimpleName());

            return Optional.empty();
        }
    }

    @Override
    public JudgeStats stats() {

        return new JudgeStats(calls.get(), failures.get(), lastLatencyMs);
    }
}
