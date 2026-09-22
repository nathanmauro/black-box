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
import java.util.concurrent.atomic.AtomicLong;
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
        this(apiKey, ENDPOINT, timeout, objectMapper, transport, questions, clock);
    }

    JevJudge(
            String apiKey,
            String endpoint,
            Duration timeout,
            ObjectMapper objectMapper,
            JevTransport transport,
            JudgeQuestionSet questions,
            Clock clock) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.requestBuilder = new JevRequestBuilder(questions);
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
        try {
            String body = objectMapper.writeValueAsString(requestBuilder.build(state));
            String response = transport.post(endpoint, apiKey, body, timeout);
            long latency = Duration.between(start, clock.instant()).toMillis();
            lastLatencyMs = latency;
            JsonNode json = objectMapper.readTree(response);

            return Optional.of(validator.validate(json, state, clock.instant(), latency));
        } catch (Exception ex) {
            failures.incrementAndGet();
            log.warn("Jev judgment failed", ex);

            return Optional.empty();
        }
    }

    @Override
    public JudgeStats stats() {

        return new JudgeStats(calls.get(), failures.get(), lastLatencyMs);
    }
}
