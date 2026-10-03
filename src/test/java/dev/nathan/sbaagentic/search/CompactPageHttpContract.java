package dev.nathan.sbaagentic.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/** Identical canonical compact paging assertions over real HTTP for SQLite and PostgreSQL. */
public final class CompactPageHttpContract {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TestRestTemplate http;
    private final String base;
    private final String key = "compact-page-" + UUID.randomUUID();

    public CompactPageHttpContract(TestRestTemplate http, String base) {
        this.http = http;
        this.base = base;
    }

    /** Baseline repro plus the fix: 230 matches with 60 exact ties, mixed fractional precision. */
    public void legacyBoundedSearchCannotTraverseButCanonicalPagesDo() throws Exception {
        List<Seeded> seeded = new ArrayList<>();
        for (int i = 0; i < 60; i++) seeded.add(seed("tie " + i, "2026-09-01T00:00:30.123456789Z", null, null));
        String[] fractions = {"", ".100", ".100200", ".100200300"};
        for (int i = 0; i < 170; i++) {
            String time = String.format("2026-09-01T00:00:%02d%sZ", i / 4 % 60, fractions[i % 4]);
            seeded.add(seed("spread " + i, time, null, null));
        }
        List<String> expected = seeded.stream()
                .sorted(Comparator.comparing(Seeded::observedAt)
                        .thenComparing(Seeded::eventId)
                        .reversed())
                .map(Seeded::eventId)
                .toList();

        JsonNode legacy =
                body(get("/api/search/compact?q=" + encode(key) + "&limit=50&maxBytes=64000&groupSimilar=false"));
        assertThat(legacy.path("items")).hasSize(50);
        assertThat(legacy.path("coverage").path("local").path("candidates").asInt())
                .isEqualTo(200);
        assertThat(legacy.path("coverage")
                        .path("local")
                        .path("candidateLimitReached")
                        .asBoolean())
                .isTrue();
        assertThat(legacy.has("nextBefore")).isFalse();
        System.out.println("Baseline legacy compact: matches=230 items=50 candidates=200 cursor=absent");

        List<String> found = new ArrayList<>();
        String cursor = null;
        String until = null;
        int pages = 0;
        do {
            JsonNode page = body(page(cursor, Map.of("limit", "7"), key));
            assertThat(page.path("status").asText()).isEqualTo("ok");
            assertThat(page.path("count").asInt()).isEqualTo(page.path("items").size());
            if (until == null) {
                until = page.path("appliedFilters").path("until").asText();
                // Events after the cutoff never join an in-progress traversal.
                seed("future arrival", "2099-01-01T00:00:00Z", null, null);
            }
            assertThat(page.path("appliedFilters").path("until").asText()).isEqualTo(until);
            page.path("items").forEach(item -> found.add(item.path("eventId").asText()));
            cursor = page.path("hasMore").asBoolean() ? page.path("nextBefore").asText() : null;
            if (cursor == null) assertThat(page.path("nextBefore").isNull()).isTrue();
        } while (cursor != null && ++pages < 100);
        assertThat(found).doesNotHaveDuplicates().hasSize(230).containsExactlyElementsOf(expected);
        System.out.println("Canonical pages traversed=" + found.size() + " pages=" + (pages + 1));
    }

    public void cutoffIsInclusiveToTheNanosecond() throws Exception {
        String before = seed("edge-before", "2026-09-02T00:00:00Z", null, null).eventId();
        String exact =
                seed("edge-exact", "2026-09-02T00:00:00.000000001Z", null, null).eventId();
        String after =
                seed("edge-after", "2026-09-02T00:00:00.000000002Z", null, null).eventId();
        assertThat(ids(body(page(null, Map.of("until", "2026-09-02T00:00:00.000000001Z"), key))))
                .containsExactly(exact, before)
                .doesNotContain(after);
        assertThat(ids(body(page(null, Map.of("until", "2026-09-02T00:00:00Z"), key))))
                .containsExactly(before);
        assertThat(ids(body(page(null, Map.of("until", "2026-09-02T00:00:00.000000002Z"), key))))
                .containsExactly(after, exact, before);
    }

    public void literalTermsMatchExactlyWithoutGrammar() throws Exception {
        String literal = seed(
                        "say \"hi\" 100%_done C:\\path\\x, a:b é🦉 kind:Decision", "2026-09-03T00:00:03Z", null, null)
                .eventId();
        String wildcard = seed("say hi 100X_done 100%Ydone", "2026-09-03T00:00:02Z", null, null)
                .eventId();
        String upper = seed("SAY \"HI\"", "2026-09-03T00:00:01Z", null, null).eventId();
        String tool = seed("tool row", "2026-09-03T00:00:00Z", "LiteralTool%", Map.of("note", "meta \"quoted\" value"))
                .eventId();

        assertThat(ids(body(page(null, Map.of(), key, "\"hi\"")))).containsExactly(literal);
        assertThat(ids(body(page(null, Map.of(), key, "100%_done")))).containsExactly(literal);
        assertThat(ids(body(page(null, Map.of(), key, "C:\\path\\x, a:b")))).containsExactly(literal);
        assertThat(ids(body(page(null, Map.of(), key, "kind:Decision")))).containsExactly(literal);
        assertThat(ids(body(page(null, Map.of(), key, "é🦉", "\"hi\"")))).containsExactly(literal);
        assertThat(ids(body(page(null, Map.of(), key, "\"HI\"")))).containsExactly(upper);
        assertThat(ids(body(page(null, Map.of(), key, "\"hi\"", "absent-term"))))
                .isEmpty();
        assertThat(ids(body(page(null, Map.of(), key, "LiteralTool%")))).containsExactly(tool);
        // Metadata is matched as stored JSON text, so the quote is matched in its escaped form.
        assertThat(ids(body(page(null, Map.of(), key, "meta \\\"quoted\\\"")))).containsExactly(tool);
        assertThat(ids(body(page(null, Map.of(), key, "100X")))).containsExactly(wildcard);
        JsonNode applied = body(page(null, Map.of(), key, "\"hi\"", key)).path("appliedFilters");
        assertThat(applied.path("terms")).hasSize(2);

        List<String> sixteen = new ArrayList<>(List.of(key));
        for (int i = 0; i < 15; i++) sixteen.add("literal-token-" + i);
        String allTerms = seed(String.join(" ", sixteen), "2026-09-03T00:00:04Z", null, null)
                .eventId();
        assertThat(ids(body(page(null, Map.of(), sixteen.toArray(String[]::new)))))
                .containsExactly(allTerms);
        String longTerm = key + "x".repeat(512 - key.length());
        String longId = seed(longTerm, "2026-09-03T00:00:05Z", null, null).eventId();
        assertThat(ids(body(page(null, Map.of(), longTerm)))).containsExactly(longId);
        String byteLimit = "🦉".repeat(512);
        String byteId = seed(byteLimit, "2026-09-03T00:00:06Z", null, null).eventId();
        assertThat(ids(body(page(null, Map.of(), byteLimit)))).containsExactly(byteId);
        assertStatus(page(null, Map.of(), byteLimit, "x"), "invalid_request");
    }

    public void projectAndSessionBoundariesAreExact() throws Exception {
        String repo = "/fixture/" + key + "/repo";
        Seeded main = seedIn("main", repo, "2026-09-04T00:00:03Z");
        Seeded slash = seedIn("slash", repo + "/", "2026-09-04T00:00:02Z");
        seedIn("sibling", repo + "-other", "2026-09-04T00:00:01Z");
        seedIn("child", repo + "/sub", "2026-09-04T00:00:00Z");
        Seeded none = seedIn("none", null, "2026-09-04T00:00:04Z");

        assertThat(ids(body(page(null, Map.of("projectExact", repo + "/"), key))))
                .containsExactly(main.eventId(), slash.eventId());
        JsonNode bySession = body(page(null, Map.of("sessionId", main.sessionId()), key));
        assertThat(ids(bySession)).containsExactly(main.eventId());
        assertThat(bySession.path("appliedFilters").path("sessionId").asText()).isEqualTo(main.sessionId());
        assertThat(ids(body(page(null, Map.of("sessionId", main.clientSessionId()), key))))
                .isEmpty();
        assertThat(ids(body(page(null, Map.of("projectExact", "__no_project__"), key))))
                .contains(none.eventId())
                .doesNotContain(main.eventId());
        assertThat(ids(body(page(null, Map.of("projectExact", repo, "sessionId", slash.sessionId()), key))))
                .containsExactly(slash.eventId());
    }

    public void cursorsAndAmbiguousRequestsFailExplicitly() throws Exception {
        for (int i = 0; i < 3; i++) seed("cursor " + i, "2026-09-05T00:00:0" + i + "Z", null, null);
        JsonNode first = body(page(null, Map.of("limit", "1"), key));
        String cursor = first.path("nextBefore").asText();
        String until = first.path("appliedFilters").path("until").asText();
        assertThat(body(page(cursor, Map.of("limit", "1"), key)))
                .isEqualTo(body(page(cursor, Map.of("limit", "1", "until", until), key)));

        assertStatus(page("!!!", Map.of(), key), "invalid_cursor");
        assertStatus(page("A".repeat(1025), Map.of(), key), "invalid_cursor");
        assertStatus(page(cursor + "=", Map.of(), key), "invalid_cursor");
        assertStatus(page(tamper(cursor, node -> node.put("v", 2)), Map.of(), key), "invalid_cursor");
        assertStatus(page(tamper(cursor, node -> node.put("x", 1)), Map.of(), key), "invalid_cursor");
        assertStatus(
                page(tamper(cursor, node -> node.put("k", "1000002099-01-01T00:00:00.000000000")), Map.of(), key),
                "invalid_cursor");
        assertStatus(
                page(tamper(cursor, node -> node.put("k", "1000002026-02-30T00:00:00.000000000")), Map.of(), key),
                "invalid_cursor");
        assertStatus(page(tamper(cursor, node -> node.put("i", "")), Map.of(), key), "invalid_cursor");
        assertStatus(page(cursor, Map.of(), key, "extra"), "cursor_mismatch");
        assertStatus(page(cursor, Map.of("until", "2026-09-05T00:00:00Z"), key), "cursor_mismatch");
        assertStatus(page(cursor, Map.of("sessionId", "other"), key), "cursor_mismatch");
        assertStatus(
                page(tamper(cursor, node -> node.put("u", "2026-09-05T00:00:02Z")), Map.of(), key), "cursor_mismatch");

        assertStatus(get("/api/search/compact?mode=canonical&term=a&q=a"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=a&groupSimilar=false"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=a&excludeSession=s"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=legacy&q=a"), "invalid_request");
        for (String parameter : List.of("term", "projectExact", "sessionId", "until", "before")) {
            assertStatus(get("/api/search/compact?q=a&" + parameter + "=a"), "invalid_request");
        }
        for (String parameter : List.of("limit", "maxBytes")) {
            var nonNumeric = get("/api/search/compact?mode=canonical&term=a&" + parameter + "=abc");
            assertThat(nonNumeric.getStatusCode().value()).isEqualTo(400);
            assertThat(body(nonNumeric).path("error").path("type").asText()).isEqualTo("invalid_argument");
        }
        assertStatus(
                get("/api/search/compact?mode=canonical&term=a&until=2026-09-05T00:00:00Z&until=2026-09-06T00:00:00Z"),
                "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=a&limit=0"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=a&limit=51"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=a&maxBytes=2047"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term="), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=" + encode("a\u0001")), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=" + encode("a\u0000")), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=" + encode("x".repeat(513))), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=a&projectExact=%20"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical&term=a&until=yesterday"), "invalid_request");
        assertStatus(get("/api/search/compact?mode=canonical" + "&term=t".repeat(17)), "invalid_request");
        var missing = get("/api/search/compact");
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(body(missing).path("error").path("type").asText()).isEqualTo("missing_parameter");
    }

    public void pagesHonorBytesWithoutSkippingAndEndCleanly() throws Exception {
        String owls = "🦉".repeat(600);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            expected.add(
                    0,
                    seed(i + " " + owls, "2026-09-06T00:00:" + (10 + i) + "Z", null, null)
                            .eventId());
        List<String> found = new ArrayList<>();
        boolean limited = false;
        String cursor = null;
        int pages = 0;
        do {
            var response = page(cursor, Map.of("limit", "10", "maxBytes", "6000"), key, owls.substring(0, 20));
            assertThat(response.getBody().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(6000);
            JsonNode page = body(response);
            assertThat(page.path("count").asInt()).isPositive();
            limited |= page.path("budgetLimited").asBoolean();
            page.path("items").forEach(item -> found.add(item.path("eventId").asText()));
            cursor = page.path("hasMore").asBoolean() ? page.path("nextBefore").asText() : null;
        } while (cursor != null && ++pages < 50);
        assertThat(limited).isTrue();
        assertThat(found).containsExactlyElementsOf(expected);

        JsonNode clipped = body(page(null, Map.of("limit", "5", "maxBytes", "2048"), key, owls.substring(0, 20)));
        assertThat(clipped.path("count").asInt()).isEqualTo(1);
        assertThat(clipped.path("budgetLimited").asBoolean()).isTrue();
        assertThat(clipped.path("excerptsTruncated").asBoolean()).isTrue();
        assertThat(clipped.path("hasMore").asBoolean()).isTrue();
        assertThat(ids(body(
                        page(clipped.path("nextBefore").asText(), Map.of("limit", "1"), key, owls.substring(0, 20)))))
                .containsExactly(expected.get(1));

        // Large literal filters leave room for the envelope but not for any hit: explicit, cursorless failure.
        List<String> terms = new ArrayList<>(List.of(key));
        StringBuilder text = new StringBuilder(key);
        for (int i = 0; i < 5; i++) {
            String term = i + "-" + "w".repeat(240);
            terms.add(term);
            text.append(' ').append(term);
        }
        seed(text.toString().substring(key.length() + 1), "2026-09-06T01:00:00Z", null, null);
        var irreducible = page(null, Map.of("maxBytes", "2048"), terms.toArray(String[]::new));
        assertThat(irreducible.getStatusCode().value()).isEqualTo(400);
        JsonNode failure = body(irreducible);
        assertThat(failure.path("status").asText()).isEqualTo("budget_exceeded");
        assertThat(failure.path("items")).isEmpty();
        assertThat(failure.path("nextBefore").isNull()).isTrue();
        assertThat(ids(body(page(null, Map.of("maxBytes", "8000"), terms.toArray(String[]::new)))))
                .hasSize(1);

        JsonNode empty = body(page(null, Map.of(), key, "no-such-literal"));
        assertThat(empty.path("count").asInt()).isZero();
        assertThat(empty.path("hasMore").asBoolean()).isFalse();
        assertThat(empty.path("nextBefore").isNull()).isTrue();
        JsonNode exact = body(page(null, Map.of("limit", "12", "maxBytes", "64000"), key, owls.substring(0, 20)));
        assertThat(exact.path("count").asInt()).isEqualTo(12);
        assertThat(exact.path("hasMore").asBoolean()).isFalse();
        assertThat(exact.path("nextBefore").isNull()).isTrue();
    }

    /** Full SqlInstant range paginates, and malformed UTF-8 inside a cursor is rejected rather than replaced. */
    public void extremeInstantsPaginateAndInvalidUtf8CursorsFail() throws Exception {
        String max = seed("instant max", Instant.MAX.toString(), null, null).eventId();
        String middle =
                seed("instant middle", "2026-09-07T00:00:00Z", null, null).eventId();
        String min = seed("instant min", Instant.MIN.toString(), null, null).eventId();
        Map<String, String> params = Map.of("limit", "1", "until", Instant.MAX.toString());
        List<String> found = new ArrayList<>();
        String cursor = null;
        String last = null;
        int pages = 0;
        do {
            JsonNode page = body(page(cursor, params, key));
            found.addAll(ids(page));
            last = cursor == null ? last : cursor;
            cursor = page.path("hasMore").asBoolean() ? page.path("nextBefore").asText() : null;
        } while (cursor != null && ++pages < 10);
        assertThat(found).containsExactly(max, middle, min);
        assertThat(ids(body(page(null, Map.of("until", Instant.MIN.toString()), key))))
                .containsExactly(min);

        byte[] json = Base64.getUrlDecoder().decode(last);
        String text = new String(json, StandardCharsets.UTF_8);
        int at = text.indexOf("\"i\":\"") + 5;
        var raw = new java.io.ByteArrayOutputStream();
        raw.write(text.substring(0, at).getBytes(StandardCharsets.UTF_8));
        raw.write(0xff);
        raw.write(text.substring(at).getBytes(StandardCharsets.UTF_8));
        assertStatus(
                page(Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()), params, key),
                "invalid_cursor");
    }

    private ResponseEntity<String> page(String before, Map<String, String> params, String... terms) {
        StringBuilder path = new StringBuilder("/api/search/compact?mode=canonical");
        for (String term : terms) path.append("&term=").append(encode(term));
        Map<String, String> all = new LinkedHashMap<>(params);
        if (before != null) all.put("before", before);
        all.forEach((name, value) -> path.append('&').append(name).append('=').append(encode(value)));

        return get(path.toString());
    }

    private ResponseEntity<String> get(String path) {

        return http.getForEntity(URI.create(base + path), String.class);
    }

    private static JsonNode body(ResponseEntity<String> response) throws Exception {

        return MAPPER.readTree(response.getBody());
    }

    private static List<String> ids(JsonNode page) {
        assertThat(page.path("status").asText()).as(page.toString()).isEqualTo("ok");

        return page.path("items").findValuesAsText("eventId");
    }

    private static void assertStatus(ResponseEntity<String> response, String status) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(body(response).path("status").asText())
                .as(response.getBody())
                .isEqualTo(status);
        JsonNode next = body(response).path("nextBefore");
        assertThat(next.isNull() || next.isMissingNode()).isTrue();
    }

    private static String tamper(String cursor, java.util.function.Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = (ObjectNode) MAPPER.readTree(Base64.getUrlDecoder().decode(cursor));
        change.accept(node);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(node));
    }

    private static String encode(String value) {

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Seeded seedIn(String label, String cwd, String time) throws Exception {
        Map<String, Object> event = new HashMap<>(Map.of(
                "source",
                "manual",
                "clientSessionId",
                key + "-" + label,
                "eventType",
                "Observation",
                "role",
                "assistant",
                "text",
                key + " " + label,
                "observedAt",
                time));
        if (cwd != null) event.put("cwd", cwd);

        return post(event, time);
    }

    private Seeded seed(String text, String time, String toolName, Map<String, Object> metadata) throws Exception {
        Map<String, Object> event = new HashMap<>(Map.of(
                "source",
                "manual",
                "clientSessionId",
                key,
                "eventType",
                "Observation",
                "role",
                "assistant",
                "text",
                key + " " + text,
                "observedAt",
                time));
        if (toolName != null) event.put("toolName", toolName);
        if (metadata != null) event.put("metadata", metadata);

        return post(event, time);
    }

    private Seeded post(Map<String, Object> event, String time) throws Exception {
        var response = http.postForEntity(URI.create(base + "/api/events"), event, String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode saved = MAPPER.readTree(response.getBody());

        return new Seeded(
                saved.path("eventId").asText(),
                saved.path("sessionId").asText(),
                saved.path("clientSessionId").asText(),
                Instant.parse(time));
    }

    private record Seeded(String eventId, String sessionId, String clientSessionId, Instant observedAt) {}
}
