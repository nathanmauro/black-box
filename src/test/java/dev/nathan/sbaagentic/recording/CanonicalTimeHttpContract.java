package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite.MemorySqlQueryAdapter;
import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** Identical real HTTP navigation assertions for SQLite and PostgreSQL. */
public final class CanonicalTimeHttpContract {
    private final TestRestTemplate http;
    private final String base;
    private final String key = "chronology-" + UUID.randomUUID();
    private final String repo = "/fixture/" + key;
    private final List<JsonNode> captured = new ArrayList<>();

    public CanonicalTimeHttpContract(TestRestTemplate http, String base) {
        this.http = http;
        this.base = base;
    }

    public void mixedTranscriptPages(TranscriptProperties properties, Path directory) throws Exception {
        Path path = directory.resolve(key + ".jsonl");
        var mapper = new ObjectMapper();
        Files.writeString(
                path,
                mapper.writeValueAsString(Map.of("type", "session_meta", "payload", Map.of("id", key)))
                        + "\n"
                        + mapper.writeValueAsString(Map.of(
                                "timestamp",
                                "2026-10-03T01:00:00.950Z",
                                "type",
                                "event_msg",
                                "payload",
                                Map.of("type", "agent_message", "message", key + " synthetic newest answer")))
                        + "\n");
        List<String> roots = properties.getCodexRoots();
        properties.setCodexRoots(List.of(directory.toString()));
        try {
            JsonNode whole =
                    capture("whole second", "2026-10-03T01:00:00Z", Map.of("transcript_path", path.toString()));
            JsonNode fraction = capture("fractional second", "2026-10-03T01:00:00.900Z", Map.of());
            JsonNode older = capture("previous second", "2026-10-03T00:59:59Z", Map.of());
            String route = "/api/sessions/" + whole.path("sessionId").asText() + "/transcript";
            List<JsonNode> found = pages(route, "events", Map.of("limit", "2", "q", key + " project_exact:" + repo));
            assertThat(found)
                    .extracting(row -> row.path("id").asText())
                    .doesNotHaveDuplicates()
                    .hasSize(4);
            assertThat(found)
                    .extracting(row -> row.path("text").asText())
                    .containsExactly(
                            key + " synthetic newest answer",
                            key + " fractional second",
                            key + " whole second",
                            key + " previous second");
            assertThat(found)
                    .extracting(row -> row.path("id").asText())
                    .contains(
                            whole.path("eventId").asText(),
                            fraction.path("eventId").asText(),
                            older.path("eventId").asText());
            assertCanonicalTimes();
        } finally {
            properties.setCodexRoots(roots);
            Files.deleteIfExists(path);
        }
    }

    public void exactWindowsAndOrdering(ZoneId zone) {
        for (String time : List.of(
                "2026-10-03T00:59:59.999999999Z",
                "2026-10-03T01:00:00Z",
                "2026-10-03T01:00:00.000000001Z",
                "2026-10-03T01:00:00.001Z",
                "2026-10-03T01:00:00.100Z",
                "2026-10-03T01:00:00.100000001Z",
                "2026-10-03T01:00:00.900Z")) capture(time, time, Map.of());
        // A matching term in another project must not cross the query's project boundary.
        post(Map.of(
                "source",
                "codex",
                "clientSessionId",
                key + "-other",
                "cwd",
                "/fixture/other",
                "eventType",
                "Observation",
                "text",
                key,
                "observedAt",
                "2026-10-03T01:00:00Z"));
        List<String> sessionEventIds = new ArrayList<>();
        get("/api/sessions/" + captured.getFirst().path("sessionId").asText() + "/events", Map.of("limit", "100"))
                .forEach(row -> sessionEventIds.add(row.path("id").asText()));
        assertThat(sessionEventIds).containsExactlyElementsOf(expected(null, null));
        String query = key + " project_exact:" + repo;
        assertWindow(query, null, null);
        assertWindow(query, "2026-10-03T01:00:00Z", "2026-10-03T01:00:00.000000001Z");
        assertWindow(query, "2026-10-03T01:00:00.100Z", "2026-10-03T01:00:00.100000001Z");
        assertWindow(query, "2026-10-03T01:00:00.000000001Z", "2026-10-03T01:00:00.000000001Z");
        assertThat(ids(
                        get("/api/events", Map.of("q", query, "since", "2026-10-03T01:00:00Z", "limit", "100")),
                        "items"))
                .containsExactlyElementsOf(expected("2026-10-03T01:00:00Z", null));
        assertThat(ids(get("/api/search", Map.of("q", key, "limit", "100")), "local"))
                .containsAll(expected(null, null));
        assertCanonicalTimes();
        // A date-only upper bound includes the last nanosecond of that local day, not the next day.
        Instant end = LocalDate.of(2026, 10, 3).plusDays(1).atStartOfDay(zone).toInstant();
        capture("date-bound", end.minusNanos(1).toString(), Map.of());
        capture("date-bound", end.toString(), Map.of());
        String dateQuery = query + " date-bound until:2026-10-03";
        String last = captured.get(captured.size() - 2).path("eventId").asText();
        assertThat(ids(get("/api/events", Map.of("q", dateQuery)), "items")).containsExactly(last);
        assertThat(ids(get("/api/search", Map.of("q", dateQuery)), "local")).containsExactly(last);
    }

    public void exactRecallWindows(MemorySqlQueryAdapter memory, RecordingCatalog catalog) {
        for (String time : List.of(
                "2026-10-03T00:59:59.999999999Z",
                "2026-10-03T01:00:00Z",
                "2026-10-03T01:00:00.000000001Z",
                "2026-10-03T01:00:00.100Z",
                "2026-10-03T01:00:00.100000001Z")) {
            capture(time, time, Map.of("transcript_path", "/fixture/" + key + "/" + time));
        }
        String sessionId = captured.getFirst().path("sessionId").asText();
        assertThat(catalog.conversationEventsForSession(sessionId))
                .extracting(AgentEvent::id)
                .containsExactlyElementsOf(expected(null, null));
        assertThat(catalog.transcriptPathsForSession(sessionId))
                .containsExactlyElementsOf(captured.stream()
                        .sorted(Comparator.comparing(CanonicalTimeHttpContract::time)
                                .reversed())
                        .map(row -> "/fixture/" + key + "/"
                                + row.path("fixtureTime").asText())
                        .toList());
        for (String since :
                List.of("2026-10-03T01:00:00Z", "2026-10-03T01:00:00.000000001Z", "2026-10-03T01:00:00.100000001Z")) {
            assertThat(memory.recallFiltered(
                            List.of("Observation"),
                            "%" + key + "%",
                            Instant.parse(since),
                            100,
                            List.of(repo),
                            true,
                            false))
                    .extracting(AgentEvent::id)
                    .containsExactlyElementsOf(expected(since, null));
            assertThat(memory.recallCandidatesFiltered(
                            List.of("Observation"), Instant.parse(since), List.of(repo), false))
                    .extracting(row -> row.event().id())
                    .containsExactlyElementsOf(expected(since, null));
        }
        List<String> typedIds = new ArrayList<>();
        IdeaEventReader.Cursor before = null;
        for (int i = 0; i < 10; i++) {
            var page = memory.eventsOfType("Observation", key, before, 2);
            page.forEach(row -> typedIds.add(row.event().id()));
            if (page.size() < 2) break;
            before = page.getLast().cursor();
        }
        assertThat(typedIds).doesNotHaveDuplicates().containsExactlyElementsOf(expected(null, null));
        assertCanonicalTimes();
    }

    public void tiedPagesAndBackdatedArrival() {
        for (int i = 0; i < 9; i++) capture("tied " + i, "2026-10-03T02:00:00.100000001Z", Map.of());
        var params = new LinkedHashMap<>(Map.of("q", key + " project_exact:" + repo, "limit", "3"));
        JsonNode first = get("/api/events", params);
        capture("backdated", "2026-10-03T02:00:00.100Z", Map.of());
        params.put("before", first.path("nextBefore").asText());
        List<JsonNode> rows = new ArrayList<>();
        first.path("items").forEach(rows::add);
        rows.addAll(pages("/api/events", "items", params));
        assertThat(rows)
                .extracting(row -> row.path("id").asText())
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(expected(null, null));
        assertCanonicalTimes();
    }

    private void assertWindow(String query, String since, String until) {
        String filtered = query + (since == null ? "" : " since:" + since) + (until == null ? "" : " until:" + until);
        List<String> expected = expected(since, until);
        assertThat(ids(get("/api/events", Map.of("q", filtered, "limit", "100")), "items"))
                .as("feed %s", filtered)
                .containsExactlyElementsOf(expected);
        assertThat(ids(get("/api/search", Map.of("q", filtered, "limit", "100")), "local"))
                .as("legacy search %s", filtered)
                .containsExactlyElementsOf(expected);
        String session = captured.getFirst().path("sessionId").asText();
        assertThat(pages("/api/sessions/" + session + "/transcript", "events", Map.of("q", filtered, "limit", "2")))
                .extracting(row -> row.path("id").asText())
                .as("session %s", filtered)
                .containsExactlyElementsOf(expected)
                .doesNotHaveDuplicates();
    }

    private List<String> expected(String since, String until) {

        return captured.stream()
                .filter(row -> since == null || !time(row).isBefore(Instant.parse(since)))
                .filter(row -> until == null || !time(row).isAfter(Instant.parse(until)))
                .sorted(Comparator.comparing(CanonicalTimeHttpContract::time)
                        .thenComparing(row -> row.path("eventId").asText())
                        .reversed())
                .map(row -> row.path("eventId").asText())
                .toList();
    }

    private static Instant time(JsonNode row) {

        return Instant.parse(row.path("fixtureTime").asText());
    }

    private JsonNode capture(String text, String time, Map<String, Object> metadata) {
        JsonNode saved = post(Map.of(
                "source",
                "codex",
                "clientSessionId",
                key,
                "cwd",
                repo,
                "eventType",
                "Observation",
                "role",
                "assistant",
                "text",
                key + " " + text,
                "observedAt",
                time,
                "metadata",
                metadata));
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved).put("fixtureTime", time);
        captured.add(saved);

        return saved;
    }

    private JsonNode post(Map<String, Object> body) {
        var response = http.postForEntity(base + "/api/events", body, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        return response.getBody();
    }

    private void assertCanonicalTimes() {
        for (JsonNode row : captured) {
            assertThat(get("/api/events/" + row.path("eventId").asText(), Map.of())
                            .path("observedAt")
                            .asText())
                    .isEqualTo(time(row).toString());
        }
    }

    private List<JsonNode> pages(String path, String field, Map<String, String> initial) {
        List<JsonNode> rows = new ArrayList<>();
        Map<String, String> params = new LinkedHashMap<>(initial);
        for (int i = 0; i < 30; i++) {
            JsonNode page = get(path, params);
            page.path(field).forEach(rows::add);
            if (page.path("nextBefore").isNull() || page.path("nextBefore").isMissingNode())

                return rows;

            params.put("before", page.path("nextBefore").asText());
        }
        throw new AssertionError("Pagination did not terminate");
    }

    private static List<String> ids(JsonNode result, String field) {
        List<String> ids = new ArrayList<>();
        result.path(field).forEach(row -> ids.add(row.path("id").asText()));

        return ids;
    }

    private JsonNode get(String path, Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        var response =
                http.getForEntity(URI.create(base + path + (query.isEmpty() ? "" : "?" + query)), JsonNode.class);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(200);

        return response.getBody();
    }
}
