package dev.nathan.sbaagentic.recording;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Shared real HTTP/database acceptance for SQLite and PostgreSQL. */
public final class SessionChronologyContract {
    private SessionChronologyContract() {}

    public static void delayedEvents(TestRestTemplate http, String base, JdbcTemplate jdbc) {
        List<List<String>> cases = List.of(
                List.of("2026-09-19T12:00:00.000000001Z", "2026-09-19T11:00:00Z"),
                List.of("2026-09-19T12:00:00.123456789Z", "2026-09-19T12:00:00.123456788Z"),
                List.of("2026-09-19T12:00:00.100Z", "2026-09-19T12:00:00Z"),
                List.of("2026-09-19T12:00:01Z", "2026-09-19T12:00:00.999999999Z"));
        for (boolean keyed : List.of(false, true)) {
            for (var times : cases) {
                String client = "delayed-" + UUID.randomUUID();
                var newer = event(client, times.get(0));
                var older = event(client, times.get(1));
                JsonNode first = post(http, base, keyed, UUID.randomUUID().toString(), newer);
                String oldKey = UUID.randomUUID().toString();
                JsonNode second = post(http, base, keyed, oldKey, older);
                String sessionId = first.path("sessionId").asText();
                assertThat(second.path("sessionId").asText()).isEqualTo(sessionId);
                JsonNode session = http.getForObject(base + "/api/sessions/" + sessionId, JsonNode.class);
                assertThat(Instant.parse(session.path("lastSeenAt").asText())).isEqualTo(Instant.parse(times.get(0)));
                assertThat(Instant.parse(session.path("startedAt").asText())).isEqualTo(Instant.parse(times.get(0)));
                assertThat(session.path("eventCount").asInt()).isEqualTo(2);
                assertThat(jdbc.queryForList("SELECT observed_at FROM agent_events WHERE session_id = ?", String.class, sessionId)
                        .stream().map(Instant::parse)).containsExactlyInAnyOrder(Instant.parse(times.get(0)), Instant.parse(times.get(1)));
                if (keyed) {
                    Map<String, Object> before = jdbc.queryForMap("SELECT * FROM agent_sessions WHERE id = ?", sessionId);
                    JsonNode replay = post(http, base, true, oldKey, older);
                    assertThat(replay.path("eventId").asText()).isEqualTo(second.path("eventId").asText());
                    assertThat(replay.path("replayed").asBoolean()).isTrue();
                    assertThat(jdbc.queryForMap("SELECT * FROM agent_sessions WHERE id = ?", sessionId)).isEqualTo(before);
                }
            }
        }
    }

    public static void concurrentEvents(TestRestTemplate http, String base, JdbcTemplate jdbc) throws Exception {
        for (boolean keyed : List.of(false, true)) {
            String client = "concurrent-time-" + UUID.randomUUID();
            Instant latest = Instant.parse("2026-09-19T12:00:00.123456789Z");
            try (var workers = Executors.newFixedThreadPool(8)) {
                var barrier = new CyclicBarrier(8);
                var results = new ArrayList<java.util.concurrent.Future<JsonNode>>();
                for (int i = 0; i < 8; i++) {
                    var body = event(client, latest.minusNanos(i).toString());
                    results.add(workers.submit(() -> {
                        barrier.await();
                        return post(http, base, keyed, UUID.randomUUID().toString(), body);
                    }));
                }
                for (var result : results) result.get(20, TimeUnit.SECONDS);
            }
            assertThat(Instant.parse(jdbc.queryForObject("SELECT last_seen_at FROM agent_sessions WHERE client_session_id = ?", String.class, client)))
                    .isEqualTo(latest);
            assertThat(jdbc.queryForObject("SELECT event_count FROM agent_sessions WHERE client_session_id = ?", Integer.class, client)).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events WHERE client_session_id = ?", Integer.class, client)).isEqualTo(8);
        }
    }

    private static Map<String, Object> event(String client, String observedAt) {
        return Map.of("source", "codex", "clientSessionId", client, "eventType", "Observation",
                "text", "Synthetic delayed capture", "observedAt", observedAt);
    }

    private static JsonNode post(TestRestTemplate http, String base, boolean keyed, String key, Map<String, Object> event) {
        var response = http.postForEntity(base + (keyed ? "/api/events/idempotent" : "/api/events"),
                keyed ? Map.of("captureId", key, "event", event) : event, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }
}
