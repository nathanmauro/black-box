package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BraidDiscoveryHttpMcpTest {
    @TempDir
    static Path temp;

    protected ServletWebServerApplicationContext app;
    protected JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;
    private String session;
    private int requestId;

    protected List<String> databaseArguments() throws Exception {

        return List.of("--spring.datasource.url=jdbc:sqlite:" + temp.resolve("discovery.db"));
    }

    protected void cleanupDatabase() throws Exception {}

    @BeforeAll
    void start() throws Exception {
        session = null;
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.location=classpath:/application.yml",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--sba.auth.enabled=false",
                "--sba.editor.enabled=false",
                "--sba.local-ai.enabled=false",
                "--sba.summary.backend=local",
                "--sba.elasticsearch.enabled=false",
                "--sba.memory.embedding.enabled=false",
                "--sba.ask.embedding-enabled=false",
                "--sba.judge.enabled=false",
                "--sba.ingestion.redact-enabled=false",
                "--sba.transcript.claude-roots[0]=" + temp.resolve("unavailable"),
                "--sba.transcript.codex-roots[0]=" + temp.resolve("unavailable"),
                "--sba.exports.targets[0].enabled=false",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"));
        args.addAll(databaseArguments());
        app = (ServletWebServerApplicationContext)
                new SpringApplicationBuilder(SbaAgenticApplication.class).run(args.toArray(String[]::new));
        mapper = app.getBean(ObjectMapper.class);
        jdbc = app.getBean(JdbcTemplate.class);
        assertThat(app.getBean(dev.nathan.sbaagentic.recording.IngestionProperties.class)
                        .isRedactEnabled())
                .isFalse();
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        rpc(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2024-11-05",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "braid-fixture", "version", "1")));
    }

    @AfterAll
    void close() throws Exception {
        try {
            if (app != null) app.close();
            client.close();
        } finally {
            cleanupDatabase();
        }
    }

    @Test
    void listsProjectOwnedDiscoveryTool() throws Exception {
        assertThat(rpc("tools/list", Map.of()).path("tools").findValuesAsText("name"))
                .contains("findBraids");
    }

    protected List<String> indexNames() {

        return jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='index'", String.class);
    }

    @Test
    void recentDiscoveryDefaultsAndIndexesSurviveApplicationRestart() throws Exception {
        String id = save(marker(), "Restart evidence", sessions(), true, true);
        JsonNode original = find(Map.of("id", id));
        JsonNode recent = find(Map.of());
        assertThat(recent.path("status").asText()).isEqualTo("ok");
        assertThat(recent.path("maxBytes").asInt()).isEqualTo(24000);
        assertThat(recent.path("count").asInt()).isBetween(1, 10);
        var ordered = jdbc.queryForList("SELECT id,created_at FROM session_melds WHERE artifact_kind='braid'");
        ordered.sort(
                Comparator.<Map<String, Object>, Instant>comparing(r -> Instant.parse((String) r.get("created_at")))
                        .thenComparing(r -> (String) r.get("id"))
                        .reversed());
        assertThat(recent.path("items").findValuesAsText("artifactId"))
                .containsExactlyElementsOf(ordered.stream()
                        .limit(recent.path("count").asInt())
                        .map(r -> (String) r.get("id"))
                        .toList());
        assertThat(indexNames())
                .contains("idx_session_melds_braid_chronology_v1", "idx_session_meld_inputs_session_meld");
        app.close();
        start();
        assertThat(find(Map.of("id", id))).isEqualTo(original);
        assertThat(indexNames())
                .contains("idx_session_melds_braid_chronology_v1", "idx_session_meld_inputs_session_meld");
    }

    @Test
    void findsBothOwnershipKindsButNotOrdinaryMeldsOrCapturedEvents() throws Exception {
        String marker = marker();
        List<String> ids = sessions();
        String assigned = save(marker, "assigned body", ids, false, true),
                unassigned = save(marker, "unassigned body", ids, true, true);
        String ordinary = save(marker, "ordinary body", ids, false, false);
        http(
                "/api/events",
                Map.of("source", "manual", "clientSessionId", marker, "eventType", "Braid", "text", marker));
        var raw = manifest();
        JsonNode found = find(Map.of("query", marker));
        assertThat(found.path("coverage").asText()).isEqualTo("saved_braids_all_ownership");
        assertThat(found.path("items").findValuesAsText("artifactId")).containsExactlyInAnyOrder(assigned, unassigned);
        for (String id : List.of(assigned, unassigned)) {
            JsonNode hit = find(Map.of("id", id)).path("items").get(0);
            assertThat(hit.path("artifactKind").asText()).isEqualTo("braid");
            assertThat(hit.path("sourceType").asText()).isEqualTo("saved_meld");
            assertThat(hit.path("providerBasis").asText()).isEqualTo("caller_declared");
            assertThat(hit.path("detailPath").asText()).isEqualTo("/api/melds/" + id);
            assertThat(hit.has("metadata")).isFalse();
            assertThat(hit.path("sessions").findValuesAsText("sessionId")).containsExactlyElementsOf(ids);
            assertThat(hit.path("ownership").asText()).isEqualTo(id.equals(assigned) ? "project" : "unassigned");
            assertThat(hit.path("projectKey").isNull()).isEqualTo(id.equals(unassigned));
            assertThat(find(Map.of("id", id)).path("nextBefore").isNull()).isTrue();
        }
        assertThat(find(Map.of("id", ordinary)).path("status").asText()).isEqualTo("not_found");
        assertThat(find(Map.of("id", marker)).path("status").asText()).isEqualTo("not_found");
        assertThat(manifest()).isEqualTo(raw);
    }

    @Test
    void literalFiltersAreCaseSensitiveAndAppliedBeforeLimit() throws Exception {
        String marker = marker();
        List<String> ids = sessions();
        String old = save("Old title", marker + " 100% _ O'Reilly 文😀 CaseNeedle", ids, true, true);
        jdbc.update("UPDATE session_melds SET created_at=? WHERE id=?", "1970-01-01T00:00:00Z", old);
        for (int i = 0; i < 22; i++) save("New distractor " + i, "No literal match", ids, true, true);
        for (String literal : List.of(marker, "100%", "_ O'Reilly", "O'Reilly", "文😀", "CaseNeedle"))
            assertThat(find(Map.of("query", "  " + literal + "  ", "limit", 1, "sessionId", ids.getFirst()))
                            .path("items")
                            .get(0)
                            .path("artifactId")
                            .asText())
                    .isEqualTo(old);
        assertThat(find(Map.of("query", "caseneedle")).path("count").asInt()).isZero();
        assertThat(find(Map.of("query", marker, "sessionId", ids.getFirst()))
                        .path("count")
                        .asInt())
                .isEqualTo(1);
        assertThat(find(Map.of("query", marker, "sessionId", sessions().getFirst()))
                        .path("count")
                        .asInt())
                .isZero();
        String clientId = jdbc.queryForObject(
                "SELECT client_session_id FROM agent_sessions WHERE id=?", String.class, ids.getFirst());
        assertThat(find(Map.of("query", marker, "sessionId", clientId))
                        .path("count")
                        .asInt())
                .isZero();
    }

    @Test
    void provenanceDistinguishesSnapshotsLegacyCurrentAndMissingSources() throws Exception {
        String marker = marker();
        List<String> ids = sessions();
        String id = save(marker, "Provenance", ids, true, true);
        String original =
                jdbc.queryForObject("SELECT cwd FROM agent_sessions WHERE id=?", String.class, ids.getFirst());
        jdbc.update("UPDATE agent_sessions SET cwd='/fixture/moved' WHERE id=?", ids.getFirst());
        jdbc.update(
                "UPDATE session_meld_inputs SET metadata_json='null' WHERE meld_id=? AND session_id=?", id, ids.get(1));
        jdbc.update(
                "INSERT INTO session_meld_inputs VALUES(?,?,?,?,?)",
                id,
                "orphan-" + marker,
                2,
                0,
                "{\"provenanceVersion\":1,\"source\":{}}");
        JsonNode hit = find(Map.of("id", id)).path("items").get(0);
        assertThat(hit.path("sessions").get(0).path("provenanceBasis").asText()).isEqualTo("save_snapshot");
        assertThat(hit.path("sessions").get(0).path("cwd").asText()).isEqualTo(original);
        assertThat(hit.path("sessions").get(1).path("provenanceBasis").asText()).isEqualTo("current_session");
        assertThat(hit.path("sessions").get(2).path("provenanceBasis").asText()).isEqualTo("unavailable");
        assertThat(hit.path("sessions").get(2).path("source").isNull()).isTrue();
        assertThat(find(Map.of("sessionId", "orphan-" + marker))
                        .path("items")
                        .get(0)
                        .path("artifactId")
                        .asText())
                .isEqualTo(id);
        jdbc.update(
                "UPDATE session_meld_inputs SET session_id=? WHERE meld_id=? AND session_id=?",
                "snapshot-deleted-" + marker,
                id,
                ids.getFirst());
        JsonNode missing =
                find(Map.of("id", id)).path("items").get(0).path("sessions").get(0);
        assertThat(missing.path("provenanceBasis").asText()).isEqualTo("save_snapshot");
        assertThat(missing.path("cwd").asText()).isEqualTo(original);
    }

    @Test
    void precisePaginationBindsFiltersAndKeepsEveryArtifactAcrossBudgetChanges() throws Exception {
        String marker = marker();
        List<String> ids = sessions();
        for (String time : List.of(
                Instant.MIN.toString(),
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00.000000001Z",
                "2026-01-01T00:00:00.1Z",
                "2026-01-01T00:00:00.1Z",
                Instant.MAX.toString())) {
            String id = save(marker, "😀".repeat(5000), ids, true, true);
            jdbc.update("UPDATE session_melds SET created_at=? WHERE id=?", time, id);
        }
        var expected = jdbc.queryForList("SELECT id,created_at FROM session_melds WHERE title=?", marker);
        expected.sort(
                Comparator.<Map<String, Object>, Instant>comparing(r -> Instant.parse((String) r.get("created_at")))
                        .thenComparing(r -> (String) r.get("id"))
                        .reversed());
        List<String> seen = new ArrayList<>();
        String before = null;
        int pages = 0;
        do {
            Map<String, Object> args = new LinkedHashMap<>(Map.of(
                    "query",
                    " " + marker + " ",
                    "sessionId",
                    ids.getFirst(),
                    "limit",
                    pages % 2 == 0 ? 3 : 2,
                    "maxBytes",
                    pages % 2 == 0 ? 2048 : 4096));
            if (before != null) args.put("before", before);
            ToolResult response = call(args);
            JsonNode page = response.json();
            assertThat(page.path("status").asText()).isEqualTo("ok");
            assertThat(response.text().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo((Integer) args.get("maxBytes"));
            seen.addAll(page.path("items").findValuesAsText("artifactId"));
            before = page.path("nextBefore").isNull()
                    ? null
                    : page.path("nextBefore").asText();
            if (pages++ == 0) {
                assertThat(before).isNotNull();
                assertThat(find(Map.of("query", marker + "x", "sessionId", ids.getFirst(), "before", before))
                                .path("status")
                                .asText())
                        .isEqualTo("invalid_request");
                assertThat(find(Map.of("query", marker, "sessionId", ids.get(1), "before", before))
                                .path("status")
                                .asText())
                        .isEqualTo("invalid_request");
            }
            assertThat(pages).isLessThan(20);
        } while (before != null);
        assertThat(seen)
                .containsExactlyElementsOf(
                        expected.stream().map(r -> (String) r.get("id")).toList());
        assertThat(new HashSet<>(seen)).hasSize(seen.size());
    }

    @Test
    void budgetPreservesAffordableOwnershipAndProvenanceBeforeBody() throws Exception {
        String project = "/fixture/" + "p".repeat(600);
        List<String> ids = List.of(seed(project), seed(project));
        String id = save("Keep source context", "😀".repeat(6000), ids, false, true);
        JsonNode fullResponse = find(Map.of("id", id, "maxBytes", 64000));
        JsonNode full = fullResponse.path("items").get(0);
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) fullResponse.deepCopy();
        var envelopeHit = (com.fasterxml.jackson.databind.node.ObjectNode)
                envelope.path("items").get(0);
        envelopeHit.put("body", "");
        envelopeHit.put("bodyTruncated", true);
        envelopeHit.put("textComplete", false);
        envelope.put("truncated", true);
        envelope.put("maxBytes", 4096);
        assertThat(mapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4096);
        ToolResult response = call(Map.of("id", id, "maxBytes", 4096));
        JsonNode hit = response.json().path("items").get(0);
        assertThat(hit.path("projectKey")).isEqualTo(full.path("projectKey"));
        assertThat(hit.path("canonicalKey")).isEqualTo(full.path("canonicalKey"));
        assertThat(hit.path("title")).isEqualTo(full.path("title"));
        assertThat(hit.path("sessions")).isEqualTo(full.path("sessions"));
        assertThat(hit.path("bodyTruncated").asBoolean()).isTrue();
        assertThat(hit.path("provenanceTruncated").asBoolean()).isFalse();
        assertThat(response.text().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
    }

    @Test
    void oversizedFieldsKeepReferencesAndValidUnicodeWithinExactJsonBudget() throws Exception {
        List<String> ids = sessions();
        String id = save("😀 title ".repeat(3000), "文😀\\\"\n".repeat(20000), ids, true, true);
        jdbc.update(
                "UPDATE session_melds SET provider=?,model=? WHERE id=?", "😀p".repeat(10000), "文m".repeat(10000), id);
        String provenance = mapper.writeValueAsString(Map.of(
                "provenanceVersion",
                1,
                "source",
                "😀".repeat(12000),
                "clientSessionId",
                "x".repeat(12000),
                "cwd",
                "/" + "文".repeat(12000)));
        jdbc.update("UPDATE session_meld_inputs SET metadata_json=? WHERE meld_id=?", provenance, id);
        var raw = manifest();
        for (int budget : List.of(2048, 4096, 24000, 64000)) {
            ToolResult response = call(Map.of("id", id, "maxBytes", budget));
            assertThat(response.text().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(budget);
            assertThat(response.json().path("status").asText()).isEqualTo("ok");
            JsonNode hit = response.json().path("items").get(0);
            assertThat(hit.path("artifactId").asText()).isEqualTo(id);
            assertThat(hit.path("sessions").findValuesAsText("sessionId")).containsExactlyElementsOf(ids);
            assertThat(hit.path("textComplete").asBoolean()).isFalse();
            assertThat(response.json().path("nextBefore").isNull()).isTrue();
            assertValidUnicode(hit);
        }
        assertThat(manifest()).isEqualTo(raw);
    }

    @Test
    void outboundRedactionIsMandatoryAndNeverLeaksAnEncodedOwnershipSecret() throws Exception {
        String secret = "sk-fixtureSyntheticABC12345678901234567890";
        String project = "/fixture/" + secret;
        List<String> ids = List.of(seed(project), seed(project));
        String id = save("Title " + secret, "Proof " + secret, ids, false, true);
        jdbc.update("UPDATE session_melds SET provider=?,model=? WHERE id=?", secret, secret, id);
        jdbc.update(
                "UPDATE session_meld_inputs SET metadata_json=? WHERE meld_id=?",
                mapper.writeValueAsString(
                        Map.of("provenanceVersion", 1, "source", secret, "clientSessionId", secret, "cwd", project)),
                id);
        var raw = manifest();
        ToolResult response = call(Map.of("id", id));
        assertThat(response.text())
                .doesNotContain(secret)
                .doesNotContain(ProjectKey.of(project).encoded());
        JsonNode hit = response.json().path("items").get(0);
        assertThat(hit.path("transformed").asBoolean()).isTrue();
        assertThat(hit.path("textComplete").asBoolean()).isFalse();
        assertThat(hit.path("ownership").asText()).isEqualTo("project");
        assertThat(hit.path("projectKey").isNull()).isTrue();
        assertThat(hit.path("sessions").get(0).path("transformed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT body FROM session_melds WHERE id=?", String.class, id))
                .contains(secret);
        assertThat(manifest()).isEqualTo(raw);
    }

    @Test
    void exportScanLimitIsDisclosedEvenWhenTheApplicationBudgetDoesNotClipAgain() throws Exception {
        String id = save(marker(), "z".repeat(60000), sessions(), true, true);
        JsonNode hit = find(Map.of("id", id, "maxBytes", 64000)).path("items").get(0);
        assertThat(hit.path("body").asText()).hasSizeLessThan(60000).contains("[truncated]");
        assertThat(hit.path("transformed").asBoolean()).isTrue();
        assertThat(hit.path("textComplete").asBoolean()).isFalse();
        assertThat(hit.path("bodyTruncated").asBoolean()).isFalse();
    }

    @Test
    void irreducibleIdentityBudgetFailsWithoutAdvancingPastTheArtifact() throws Exception {
        String marker = marker();
        String id = save(marker, "Body", sessions(), true, true);
        for (int i = 0; i < 30; i++)
            jdbc.update(
                    "INSERT INTO session_meld_inputs VALUES(?,?,?,?,?)",
                    id,
                    "historic-" + i + "x".repeat(150),
                    i + 2,
                    0,
                    null);
        ToolResult response = call(Map.of("query", marker, "maxBytes", 2048));
        assertThat(response.json().path("status").asText()).isEqualTo("budget_exceeded");
        assertThat(response.json().path("count").asInt()).isZero();
        assertThat(response.json().path("nextBefore").isNull()).isTrue();
        assertThat(response.text().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        assertThat(find(Map.of("query", marker, "maxBytes", 64000))
                        .path("items")
                        .get(0)
                        .path("artifactId")
                        .asText())
                .isEqualTo(id);
    }

    @Test
    void firstHitBecomesReferenceOnlyInsteadOfBeingSkipped() throws Exception {
        List<String> ids = sessions();
        String id = save("Long title ".repeat(1000), "Long body ".repeat(1000), ids, true, true);
        String prior = ids.getFirst();
        int low = 1, high = 4096, best = 0;
        while (low <= high) {
            int middle = (low + high) / 2;
            String candidate = "r".repeat(middle);
            jdbc.update(
                    "UPDATE session_meld_inputs SET session_id=? WHERE meld_id=? AND session_id=?",
                    candidate,
                    id,
                    prior);
            prior = candidate;
            if (find(Map.of("id", id, "maxBytes", 2048)).path("status").asText().equals("ok")) {
                best = middle;
                low = middle + 1;
            } else high = middle - 1;
        }
        String chosen = "r".repeat(best);
        jdbc.update("UPDATE session_meld_inputs SET session_id=? WHERE meld_id=? AND session_id=?", chosen, id, prior);
        ToolResult result = call(Map.of("id", id, "maxBytes", 2048));
        JsonNode hit = result.json().path("items").get(0);
        assertThat(hit.path("referenceOnly").asBoolean()).isTrue();
        assertThat(hit.path("artifactId").asText()).isEqualTo(id);
        assertThat(hit.path("sessions").get(0).path("sessionId").asText()).isEqualTo(chosen);
        assertThat(hit.path("title").asText()).isEmpty();
        assertThat(hit.path("body").asText()).isEmpty();
        assertThat(result.text().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
    }

    @Test
    void nonportableSelectorCodeUnitsFailBeforeDatabaseAccess() throws Exception {
        var raw = manifest();
        for (String field : List.of("query", "id", "sessionId")) {
            for (String value : List.of("nul\u0000value", "high\uD800value", "low\uDC00value")) {
                ToolResult response = call(Map.of(field, value));
                assertThat(response.json().path("status").asText()).isEqualTo("invalid_request");
                assertThat(response.text()).doesNotContain(value);
                assertThat(response.json().path("diagnostics").get(0).asText()).contains("Unicode");
            }
        }
        String cursor = new dev.nathan.sbaagentic.project.internal.domain.BraidDiscoveryCursor(
                        Instant.EPOCH,
                        "a\u0000b",
                        dev.nathan.sbaagentic.project.internal.domain.BraidDiscoveryCursor.fingerprint(null, null))
                .encoded();
        JsonNode response = find(Map.of("before", cursor));
        assertThat(response.path("status").asText()).isEqualTo("invalid_request");
        assertThat(response.path("nextBefore").isNull()).isTrue();
        assertThat(manifest()).isEqualTo(raw);
    }

    @Test
    void invalidSelectionsReturnBoundedActionableErrorsWithoutWrites() throws Exception {
        var raw = manifest();
        for (Map<String, Object> args : List.<Map<String, Object>>of(
                Map.of("limit", 0),
                Map.of("limit", 21),
                Map.of("maxBytes", 2047),
                Map.of("maxBytes", 64001),
                Map.of("query", " "),
                Map.of("query", "x".repeat(1025)),
                Map.of("sessionId", " "),
                Map.of("id", " "),
                Map.of("id", "id", "query", "q"),
                Map.of("id", "id", "sessionId", "s"),
                Map.of("id", "id", "before", "cursor"),
                Map.of("before", ""),
                Map.of("before", "broken"))) {
            ToolResult response = call(args);
            assertThat(response.json().path("status").asText()).isEqualTo("invalid_request");
            if (args.containsKey("maxBytes"))
                assertThat(response.json().path("maxBytes").asInt()).isEqualTo(2048);
            assertThat(response.json().path("diagnostics").get(0).asText())
                    .isNotBlank()
                    .doesNotContain("java.");
            assertThat(response.text().getBytes(StandardCharsets.UTF_8).length).isLessThan(2048);
        }
        assertThat(find(Map.of("query", "x".repeat(1024))).path("status").asText())
                .isEqualTo("ok");
        assertThat(manifest()).isEqualTo(raw);
    }

    private String marker() {

        return "braid-" + UUID.randomUUID();
    }

    private List<String> sessions() throws Exception {
        String project = "/fixture/" + marker();

        return List.of(seed(project), seed(project));
    }

    private String seed(String project) throws Exception {

        return http(
                        "/api/events",
                        Map.of(
                                "source",
                                "manual",
                                "clientSessionId",
                                marker(),
                                "cwd",
                                project,
                                "eventType",
                                "Observation",
                                "text",
                                "Original captured evidence"))
                .path("sessionId")
                .asText();
    }

    private String save(String title, String body, List<String> sessions, boolean unassigned, boolean braid)
            throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(Map.of(
                "title",
                title,
                "body",
                body,
                "sessionIds",
                sessions,
                "metadata",
                Map.of("kind", braid ? "braid" : "meld", "evidenceIds", List.of("opaque-caller-claim"))));
        if (!unassigned)
            args.put(
                    "projectKey",
                    ProjectKey.of(jdbc.queryForObject(
                                    "SELECT cwd FROM agent_sessions WHERE id=?", String.class, sessions.getFirst()))
                            .encoded());

        return http("/api/melds", args).path("id").asText();
    }

    private Object manifest() {

        return List.of(
                jdbc.queryForList("SELECT * FROM session_melds ORDER BY id"),
                jdbc.queryForList("SELECT * FROM session_meld_inputs ORDER BY meld_id,input_order"),
                jdbc.queryForList("SELECT * FROM agent_events ORDER BY id"));
    }

    private JsonNode find(Map<String, Object> args) throws Exception {

        return call(args).json();
    }

    private ToolResult call(Map<String, Object> args) throws Exception {
        JsonNode result = rpc("tools/call", Map.of("name", "findBraids", "arguments", args));
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        String text = result.path("content").get(0).path("text").asText();

        return new ToolResult(text, mapper.readTree(text));
    }

    private void assertValidUnicode(JsonNode node) {
        if (node.isTextual())
            assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(node.asText()))
                    .isTrue();
        else node.elements().forEachRemaining(this::assertValidUnicode);
    }

    private record ToolResult(String text, JsonNode json) {}

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        JsonNode response =
                http("/mcp", Map.of("jsonrpc", "2.0", "id", ++requestId, "method", method, "params", params));
        assertThat(response.has("error")).as(response.toString()).isFalse();

        return response.path("result");
    }

    private JsonNode http(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (path.equals("/mcp") && session != null) request.header("Mcp-Session-Id", session);
        var response = client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(mapper.writer()
                                .with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature())
                                .writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        if (path.equals("/mcp")) response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
        String content = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
            content = content.lines()
                    .filter(s -> s.startsWith("data:"))
                    .map(s -> s.substring(5).strip())
                    .findFirst()
                    .orElseThrow();

        return mapper.readTree(content);
    }
}
