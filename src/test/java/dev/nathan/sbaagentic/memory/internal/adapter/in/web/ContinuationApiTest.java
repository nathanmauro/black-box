package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationPromptRenderer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/**
 * {@code GET /api/continuations} over real HTTP against an isolated canonical store, seeded through
 * the public capture APIs with timestamps relative to now. No model, index or embedder runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContinuationApiTest {
    @TempDir
    static Path temp;

    private final HttpClient client = HttpClient.newHttpClient();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private String base;

    @BeforeAll
    void start() throws Exception {
        Map<String, Object> database = databaseProperties();
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .profiles(databaseProfiles())
                .initializers(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new org.springframework.core.env.MapPropertySource(
                                "continuation-fixture-database", database)))
                .run(
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
                        "--sba.transcript.claude-roots[0]=" + temp.resolve("unavailable"),
                        "--sba.transcript.codex-roots[0]=" + temp.resolve("unavailable"),
                        "--sba.exports.targets[0].enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        mapper = app.getBean(ObjectMapper.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    protected Map<String, Object> databaseProperties() throws Exception {

        return Map.of("spring.datasource.url", "jdbc:sqlite:" + temp.resolve("continuation.db"));
    }

    protected String[] databaseProfiles() {

        return new String[0];
    }

    protected void cleanupDatabase() throws Exception {}

    @AfterAll
    void stop() throws Exception {
        try {
            if (app != null) app.close();
            client.close();
        } finally {
            cleanupDatabase();
        }
    }

    @Test
    void includesAliasCapturesExcludesOtherProjectsAndRanksByTierThenRecency() throws Exception {
        String root = project(), alias = root + "-wt", other = root + "-other";
        alias(alias, root);
        JsonNode rootHandoff = handoff(
                root,
                "root",
                Duration.ofHours(3),
                "Storage replaced; recovery unverified",
                "Reproduce the recovery path",
                "Measure restore time");
        JsonNode aliasHandoff =
                handoff(alias, "alias", Duration.ofHours(2), "Worktree slice verified", "Merge the worktree slice");
        handoff(other, "other", Duration.ofHours(1), "OTHER-PROJECT summary", "OTHER-PROJECT step", "OTHER loop");
        decision(
                root,
                "root-decision",
                "Keep SQLite as the local default",
                "Local-first",
                null,
                "Document the backup command");

        HttpResponse<String> response = get("project", root);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Blackbox-Recall-Id")).isPresent();
        assertThat(response.body()).doesNotContain("OTHER-PROJECT").doesNotContain("OTHER loop");
        JsonNode body = mapper.readTree(response.body());

        assertThat(body.path("project").path("canonicalKey").asText()).isEqualTo(root);
        assertThat(texts(body.path("project").path("scopes"), "canonicalKey")).containsExactly(root, alias);
        assertThat(body.path("mode").asText()).isEqualTo("deterministic");
        assertThat(body.path("withinHours").asInt()).isEqualTo(8760);
        assertThat(body.path("stale").asBoolean()).isFalse();
        assertThat(body.path("retrieval").path("handoffs").asInt()).isEqualTo(2);
        assertThat(body.path("retrieval").path("decisions").asInt()).isEqualTo(1);
        assertThat(body.path("retrieval").path("poolCap").asInt()).isEqualTo(50);
        assertThat(body.path("retrieval").path("excludedReplaced").asBoolean()).isTrue();
        assertThat(texts(body.path("warnings"), "code")).containsExactly("historical_evidence");
        assertThat(body.path("warnings").path(0).path("message").asText())
                .isEqualTo("Historical evidence does not prove an action is still relevant or unfinished.");

        JsonNode candidates = body.path("candidates");
        assertThat(texts(candidates, "basis"))
                .containsExactly("handoff.nextAction", "handoff.nextAction", "handoff.openLoop");
        assertThat(texts(candidates, "title"))
                .containsExactly("Merge the worktree slice", "Reproduce the recovery path", "Measure restore time");
        JsonNode first = candidates.path(0);
        assertThat(first.path("rank").asInt()).isEqualTo(1);
        assertThat(first.path("id").asText()).matches("bbc1-[0-9a-f]{24}");
        assertThat(first.path("ageDays").asInt()).isZero();
        assertThat(first.path("stale").asBoolean()).isFalse();
        assertThat(first.path("why").asText())
                .isEqualTo("Recorded as the next action in the newest handoff of session "
                        + aliasHandoff.path("sessionId").asText() + " on "
                        + first.path("evidence")
                                .path(0)
                                .path("observedAt")
                                .asText()
                                .substring(0, 10) + ".");
        JsonNode evidence = first.path("evidence").path(0);
        assertThat(evidence.path("eventId").asText())
                .isEqualTo(aliasHandoff.path("eventId").asText());
        assertThat(evidence.path("kind").asText()).isEqualTo("handoff");
        assertThat(evidence.path("field").asText()).isEqualTo("nextAction");
        assertThat(evidence.path("href").asText())
                .isEqualTo("/?view=browse&session="
                        + aliasHandoff.path("sessionId").asText() + "&event="
                        + aliasHandoff.path("eventId").asText() + "&project=");
        String prompt = candidates.path(1).path("prompt").asText();
        assertThat(prompt)
                .startsWith("Black Box continuation prompt — built from records captured ")
                .contains(ContinuationPromptRenderer.BOUNDARY)
                .contains("```text\nReproduce the recovery path\n```")
                .contains("```text\nStorage replaced; recovery unverified\n```")
                .contains("```text\nKeep SQLite as the local default\n```")
                .contains("event " + rootHandoff.path("eventId").asText() + ": /?view=browse")
                .endsWith(ContinuationPromptRenderer.LINK_NOTE);
        assertThat(prompt.length()).isLessThanOrEqualTo(ContinuationPromptRenderer.BUDGET);

        JsonNode capped = json(get("project", root, "max", "1"));
        assertThat(texts(capped.path("candidates"), "title")).containsExactly("Merge the worktree slice");
    }

    @Test
    void anAliasPathOrTrailingSlashResolvesToTheSameRoot() throws Exception {
        String root = project(), alias = root + "-worktree";
        alias(alias, root);
        handoff(alias, "alias", Duration.ofHours(1), "Alias state", "Finish the alias slice");

        JsonNode viaRoot = json(get("project", root));
        JsonNode viaAlias = json(get("project", alias));
        JsonNode viaSlash = json(get("project", root + "/"));

        for (JsonNode response : List.of(viaAlias, viaSlash)) {
            assertThat(response.path("project").path("canonicalKey").asText()).isEqualTo(root);
            assertThat(texts(response.path("candidates"), "id"))
                    .containsExactlyElementsOf(texts(viaRoot.path("candidates"), "id"));
        }
        assertThat(texts(viaRoot.path("candidates"), "title")).containsExactly("Finish the alias slice");
    }

    @Test
    void aReplacedDecisionContributesNothing() throws Exception {
        String root = project();
        JsonNode replaced = decision(root, "replaced", "Use SQLite storage REPLACED", "Simple", null, "REPLACED loop");
        decision(
                root,
                "replacement",
                "Use PostgreSQL for shared storage",
                "Shared deployment now requires remote access",
                replaced.path("eventId").asText(),
                "Measure the restore drill");

        HttpResponse<String> response = get("project", root);
        JsonNode body = json(response);

        assertThat(response.body())
                .doesNotContain("REPLACED")
                .doesNotContain(replaced.path("eventId").asText());
        assertThat(texts(body.path("candidates"), "basis")).containsExactly("decision.openLoop");
        assertThat(texts(body.path("candidates"), "title")).containsExactly("Measure the restore drill");
        assertThat(body.path("candidates").path(0).path("prompt").asText())
                .contains("No handoff recorded in the window.")
                .contains("```text\nUse PostgreSQL for shared storage\n```");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "/", "///", "__no_project__", " __no_project__/ "})
    void blankRootAndNoProjectAreRejected(String project) throws Exception {
        HttpResponse<String> response = get("project", project);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("type").asText()).isEqualTo("invalid_argument");
    }

    @Test
    void missingProjectAndOutOfRangeBoundsAreRejected() throws Exception {
        String root = project();
        List<HttpResponse<String>> responses = new ArrayList<>();
        responses.add(send("/api/continuations"));
        for (String[] query : List.of(
                new String[] {"withinHours", "0"},
                new String[] {"withinHours", "8761"},
                new String[] {"withinHours", "soon"},
                new String[] {"max", "0"},
                new String[] {"max", "4"})) {
            responses.add(get("project", root, query[0], query[1]));
        }

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("error").path("type").asText()).isEqualTo("invalid_argument");
        }
        assertThat(get("project", root, "withinHours", "1", "max", "3").statusCode())
                .isEqualTo(200);
    }

    @Test
    void anUnknownProjectReturnsNoCandidatesAndAnInsufficientEvidenceWarning() throws Exception {
        JsonNode body = json(get("project", project()));

        assertThat(body.path("candidates").isArray()).isTrue();
        assertThat(body.path("candidates")).isEmpty();
        assertThat(texts(body.path("warnings"), "code"))
                .containsExactly("historical_evidence", "insufficient_evidence");
        assertThat(body.path("headAt").isNull()).isTrue();
        assertThat(body.path("newestEvidenceAt").isNull()).isTrue();
        assertThat(body.path("stale").asBoolean()).isFalse();
        assertThat(body.path("retrieval").path("handoffs").asInt()).isZero();
    }

    @Test
    void noActionTextIsNeverPadded() throws Exception {
        String root = project();
        handoff(root, "quiet", Duration.ofHours(1), "Wrapped up", "None", "n/a", "Done.");

        JsonNode body = json(get("project", root));

        assertThat(body.path("candidates")).isEmpty();
        assertThat(texts(body.path("warnings"), "code"))
                .containsExactly("historical_evidence", "insufficient_evidence");
    }

    @Test
    void staleAndFutureDatedCapturesAreLabelled() throws Exception {
        String stale = project();
        handoff(stale, "old", Duration.ofDays(20), "Old state", "Resume the migration");
        JsonNode old = json(get("project", stale));
        assertThat(old.path("stale").asBoolean()).isTrue();
        assertThat(old.path("candidates").path(0).path("stale").asBoolean()).isTrue();
        assertThat(old.path("candidates").path(0).path("ageDays").asInt()).isEqualTo(20);
        assertThat(texts(old.path("warnings"), "code")).containsExactly("historical_evidence", "stale");

        String future = project();
        handoff(future, "ahead", Duration.ofDays(-2), "Forward dated state", "Check the capture clock");
        JsonNode ahead = json(get("project", future));
        JsonNode candidate = ahead.path("candidates").path(0);
        assertThat(candidate.path("ageDays").asInt()).isZero();
        assertThat(texts(candidate.path("caveats"), "code")).containsExactly("future_dated");
        assertThat(texts(ahead.path("warnings"), "code")).containsExactly("historical_evidence", "future_dated");
        assertThat(Instant.parse(
                        candidate.path("evidence").path(0).path("observedAt").asText()))
                .isAfter(Instant.parse(ahead.path("generatedAt").asText()));
    }

    @Test
    void explicitEvidenceLinksBecomeCaveatsWithoutChangingRank() throws Exception {
        String root = project();
        JsonNode first = handoff(root, "first", Duration.ofHours(2), "First state", "Ship the parser");
        handoff(root, "second", Duration.ofHours(1), "Second state", "Write the parser docs");
        String target = first.path("eventId").asText();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "codex");
        evidence.put("clientSessionId", "evidence-" + UUID.randomUUID());
        evidence.put("repo", root);
        evidence.put("claim", "The parser already shipped in release 4");
        evidence.put("sourceRef", "release-notes.md:4");
        evidence.put("refutes", List.of("event:" + target));
        JsonNode linked = mapper.readTree(post("/api/evidence", evidence).body());

        JsonNode body = json(get("project", root));
        JsonNode shipped = body.path("candidates").path(1);

        assertThat(texts(body.path("candidates"), "title")).containsExactly("Write the parser docs", "Ship the parser");
        assertThat(texts(shipped.path("caveats"), "code")).containsExactly("evidence_refutes");
        assertThat(shipped.path("caveats").path(0).path("eventId").asText())
                .isEqualTo(linked.path("eventId").asText());
        assertThat(shipped.path("caveats").path(0).path("href").asText())
                .isEqualTo("/?view=browse&session=" + linked.path("sessionId").asText() + "&event="
                        + linked.path("eventId").asText() + "&project=");
        assertThat(texts(shipped.path("evidence"), "field")).containsExactly("nextAction", "refutes");
        assertThat(shipped.path("prompt").asText())
                .contains("refutes source event " + target)
                .contains("```text\nThe parser already shipped in release 4\n```");
        assertThat(body.path("candidates").path(0).path("caveats")).isEmpty();
    }

    private String project() {

        return "/fixture/continuation-" + UUID.randomUUID();
    }

    private void alias(String alias, String canonical) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/api/project-aliases"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("aliasKey", alias, "canonicalKey", canonical))))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    /** Through {@code POST /api/events} in the structured-capture shape, so the time can be relative. */
    private JsonNode handoff(
            String repo, String client, Duration age, String summary, String nextAction, String... loops)
            throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "handoff");
        metadata.put("contextSummary", summary);
        metadata.put("openLoops", List.of(loops));
        metadata.put("nextAction", nextAction);
        metadata.put("repo", repo);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "codex");
        event.put("clientSessionId", client + "-" + UUID.randomUUID());
        event.put("eventType", "Handoff");
        event.put("role", "assistant");
        event.put("text", "Handoff: " + summary);
        event.put("cwd", repo);
        event.put("metadata", metadata);
        event.put("observedAt", Instant.now().minus(age).toString());
        HttpResponse<String> response = post("/api/events", event);
        assertThat(response.statusCode()).isEqualTo(200);

        return mapper.readTree(response.body());
    }

    private JsonNode decision(
            String repo, String client, String decision, String rationale, String supersedes, String... loops)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("source", "codex");
        request.put("clientSessionId", client + "-" + UUID.randomUUID());
        request.put("repo", repo);
        request.put("decision", decision);
        request.put("rationale", rationale);
        request.put("openLoops", List.of(loops));
        if (supersedes != null) request.put("supersedes", supersedes);
        HttpResponse<String> response = post("/api/decisions", request);
        assertThat(response.statusCode()).isEqualTo(200);

        return mapper.readTree(response.body());
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {

        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build());
    }

    private HttpResponse<String> get(String... pairs) throws Exception {
        StringBuilder query = new StringBuilder();
        for (int index = 0; index < pairs.length; index += 2) {
            query.append(index == 0 ? "?" : "&")
                    .append(pairs[index])
                    .append('=')
                    .append(URLEncoder.encode(pairs[index + 1], StandardCharsets.UTF_8));
        }

        return send("/api/continuations" + query);
    }

    private HttpResponse<String> send(String path) throws Exception {

        return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {

        return mapper.readTree(response.body());
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));

        return values;
    }
}
