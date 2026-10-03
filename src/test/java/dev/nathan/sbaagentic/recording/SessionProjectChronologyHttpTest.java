package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectCatalogStore;
import dev.nathan.sbaagentic.query.SqlInstant;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real capture/read HTTP paths; PostgreSQL uses only a fresh, disposable schema when opted in. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionProjectChronologyHttpTest {
    @TempDir
    static Path tempDir;

    private final TestRestTemplate http = new TestRestTemplate();
    private final Map<String, Fixture> fixtures = new LinkedHashMap<>();
    private final String schema =
            "bb_session_time_" + UUID.randomUUID().toString().replace("-", "");
    private boolean schemaCreated;

    @BeforeAll
    void startFixtures() throws Exception {
        start("sqlite", List.of("--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("sessions.db")));
        String url = System.getenv("SBA_POSTGRES_TEST_URL");
        if (url != null && url.startsWith("jdbc:postgresql:")) {
            try (Connection connection = postgresConnection()) {
                connection.createStatement().execute("CREATE SCHEMA " + schema);
                schemaCreated = true;
            }
            start(
                    "postgres",
                    List.of(
                            "--spring.profiles.active=postgres",
                            "--spring.datasource.url=" + url,
                            "--spring.datasource.username=" + postgresUsername(),
                            "--spring.datasource.password=" + postgresPassword(),
                            "--spring.datasource.hikari.data-source-properties.currentSchema=" + schema));
        }
    }

    private void start(String backend, List<String> databaseArgs) {
        var args = new ArrayList<>(List.of(
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
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"));
        args.addAll(databaseArgs);
        var app = (ServletWebServerApplicationContext)
                new SpringApplicationBuilder(SbaAgenticApplication.class).run(args.toArray(String[]::new));
        fixtures.put(
                backend,
                new Fixture(
                        app,
                        app.getBean(JdbcTemplate.class),
                        "http://127.0.0.1:" + app.getWebServer().getPort()));
    }

    @AfterAll
    void stopFixtures() throws Exception {
        for (Fixture fixture : fixtures.values()) fixture.app().close();
        if (schemaCreated) {
            try (Connection connection = postgresConnection()) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgres"})
    void publicSessionListsAndSelectionUseInstantsWithStableIdTies(String backend) {
        Fixture f = fixture(backend);
        String repo = scope();
        List<Seed> seeds = new ArrayList<>();
        for (String at : List.of(
                "2026-10-03T12:00:00Z",
                "2026-10-03T12:00:00.000000001Z",
                "2026-10-03T12:00:00.100Z",
                "2026-10-03T12:00:00.100Z",
                "2026-10-03T12:00:00.123456789Z",
                "2026-10-03T12:00:00.999999999Z",
                "2026-10-03T12:00:01Z",
                "9999-12-31T23:59:59.999999999Z",
                "+10000-01-01T00:00:00Z",
                "-0001-01-01T00:00:00Z")) {
            seeds.add(capture(f, repo, UUID.randomUUID().toString(), at, false));
        }
        List<String> expected = seeds.stream()
                .sorted(Comparator.comparing(Seed::at).thenComparing(Seed::id).reversed())
                .map(Seed::id)
                .toList();
        String key = project(f, repo).path("projectKey").asText();
        var softly = new SoftAssertions();
        softly.assertThat(idsForRepo(get(f, "/api/sessions?limit=500"), repo)).isEqualTo(expected);
        softly.assertThat(idsForRepo(get(f, "/api/sessions?limit=500&humanOnly=true"), repo))
                .isEqualTo(expected);
        softly.assertThat(ids(get(f, "/api/projects/" + key + "/sessions?limit=500")))
                .isEqualTo(expected);
        softly.assertThat(ids(get(f, "/api/projects/" + key + "/sessions?limit=1")))
                .containsExactly(expected.get(0));
        var catalog = f.app().getBean(ProjectCatalogStore.class);
        softly.assertThat(catalog
                        .sessionsForProjectByIds(
                                repo, seeds.stream().map(Seed::id).toList())
                        .stream()
                        .map(AgentSession::id)
                        .toList())
                .isEqualTo(expected);
        softly.assertThat(f.app().getBean(RecordingCatalog.class).recentSessionsMissingSummary(500).stream()
                        .filter(session -> repo.equals(session.cwd()))
                        .map(AgentSession::id)
                        .toList())
                .isEqualTo(expected);
        for (Seed seed : seeds) {
            var session = get(f, "/api/sessions/" + seed.id());
            softly.assertThat(session.path("startedAt").asText())
                    .isEqualTo(seed.at().toString());
            softly.assertThat(session.path("lastSeenAt").asText())
                    .isEqualTo(seed.at().toString());
            softly.assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT last_seen_at FROM agent_sessions WHERE id = ?", String.class, seed.id()))
                    .isEqualTo(seed.at().toString());
        }
        String child = seeds.get(0).id();
        f.jdbc()
                .update(
                        "UPDATE agent_sessions SET spawned_by = ? WHERE id = ?",
                        seeds.get(1).id(),
                        child);
        softly.assertThat(idsForRepo(get(f, "/api/sessions?limit=500"), repo)).doesNotContain(child);
        softly.assertThat(idsForRepo(get(f, "/api/sessions?limit=500&includeChildren=true"), repo))
                .isEqualTo(expected);
        f.jdbc()
                .update(
                        "UPDATE agent_sessions SET first_human_turn = NULL WHERE id = ?",
                        seeds.get(1).id());
        softly.assertThat(idsForRepo(get(f, "/api/sessions?limit=500&humanOnly=true"), repo))
                .doesNotContain(child, seeds.get(1).id());
        softly.assertAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgres"})
    void projectSummaryExtremaAndRankingUseInstantsWithinEachScope(String backend) {
        Fixture f = fixture(backend);
        var softly = new SoftAssertions();
        for (List<String> pair : List.of(
                List.of("2026-10-03T12:00:00Z", "2026-10-03T12:00:00.100Z"),
                List.of("2026-10-03T12:00:00.123456788Z", "2026-10-03T12:00:00.123456789Z"),
                List.of("9999-12-31T23:59:59.999999999Z", "+10000-01-01T00:00:00Z"),
                List.of("-10000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z"))) {
            String repo = scope();
            capture(f, repo, UUID.randomUUID().toString(), pair.get(0), false);
            capture(f, repo, UUID.randomUUID().toString(), pair.get(1), false);
            JsonNode project = project(f, repo);
            softly.assertThat(project.path("firstSeenAt").asText()).isEqualTo(pair.get(0));
            softly.assertThat(project.path("lastSeenAt").asText()).isEqualTo(pair.get(1));
            softly.assertThat(project.path("sessionCount").asInt()).isEqualTo(2);
            softly.assertThat(project.path("eventCount").asInt()).isEqualTo(2);
        }
        String primary = scope();
        String alias = scope();
        String neighbor = scope();
        capture(f, primary, UUID.randomUUID().toString(), "2026-10-03T12:00:00Z", false);
        capture(f, primary, UUID.randomUUID().toString(), "2026-10-03T12:00:00.900Z", false);
        capture(f, alias, UUID.randomUUID().toString(), "2026-10-03T12:00:00.200Z", false);
        capture(f, neighbor, UUID.randomUUID().toString(), "2026-10-03T12:00:00.500Z", false);
        http.put(f.base() + "/api/project-aliases", Map.of("aliasKey", alias, "canonicalKey", primary));
        JsonNode project = project(f, primary);
        softly.assertThat(project.path("firstSeenAt").asText()).isEqualTo("2026-10-03T12:00:00Z");
        softly.assertThat(project.path("lastSeenAt").asText()).isEqualTo("2026-10-03T12:00:00.900Z");
        softly.assertThat(project.path("sessionCount").asInt()).isEqualTo(3);
        softly.assertThat(nodes(get(f, "/api/projects")).stream()
                        .map(p -> p.path("canonicalKey").asText())
                        .filter(p -> p.equals(primary) || p.equals(neighbor))
                        .toList())
                .containsExactly(primary, neighbor);
        softly.assertAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgres"})
    void savedMeldExtremaAffectPublicProjectChronologyWithoutChangingStoredValues(String backend) {
        Fixture f = fixture(backend);
        String repo = scope();
        Seed source = capture(f, repo, UUID.randomUUID().toString(), "2026-10-03T12:00:00.050Z", false);
        String key = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(repo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> times = List.of("2026-10-03T12:00:00Z", "2026-10-03T12:00:00.100Z");
        List<String> meldIds = new ArrayList<>();
        for (String at : times) {
            JsonNode meld = post(
                    f,
                    "/api/melds",
                    Map.of(
                            "projectKey",
                            key,
                            "title",
                            "Synthetic saved context",
                            "body",
                            "Fixture evidence",
                            "sessionIds",
                            List.of(source.id())));
            String id = meld.path("id").asText();
            assertThat(id).isNotBlank();
            f.jdbc().update("UPDATE session_melds SET created_at = ? WHERE id = ?", at, id);
            meldIds.add(id);
        }
        String neighbor = scope();
        capture(f, neighbor, UUID.randomUUID().toString(), "2026-10-03T12:00:00.075Z", false);
        JsonNode project = project(f, repo);
        var softly = new SoftAssertions();
        softly.assertThat(nodes(get(f, "/api/projects")).stream()
                        .map(p -> p.path("canonicalKey").asText())
                        .filter(p -> p.equals(repo) || p.equals(neighbor))
                        .toList())
                .containsExactly(repo, neighbor);
        softly.assertThat(project.path("firstSeenAt").asText()).isEqualTo(times.get(0));
        softly.assertThat(project.path("lastSeenAt").asText()).isEqualTo(times.get(1));
        softly.assertThat(project.path("savedMeldCount").asInt()).isEqualTo(2);
        softly.assertThat(project.path("sessionCount").asInt()).isEqualTo(1);
        for (int i = 0; i < meldIds.size(); i++) {
            softly.assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT created_at FROM session_melds WHERE id = ?", String.class, meldIds.get(i)))
                    .isEqualTo(times.get(i));
        }
        softly.assertAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgres"})
    void firstCaptureOriginAndLatestSeenRemainUnchangedByReadOrdering(String backend) {
        Fixture f = fixture(backend);
        for (boolean keyed : List.of(false, true)) {
            String repo = scope();
            String client = UUID.randomUUID().toString();
            Seed first = capture(f, repo, client, "2026-10-03T12:00:00Z", keyed);
            capture(f, repo, client, "2026-10-03T12:00:00.000000001Z", keyed);
            capture(f, repo, client, "2026-10-03T11:59:59.999999999Z", keyed);
            JsonNode session = get(f, "/api/sessions/" + first.id());
            assertThat(session.path("startedAt").asText()).isEqualTo("2026-10-03T12:00:00Z");
            assertThat(session.path("lastSeenAt").asText()).isEqualTo("2026-10-03T12:00:00.000000001Z");
            assertThat(session.path("eventCount").asInt()).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgres"})
    void normalizedIndexAndAggregatesHandleTwentyThousandSessions(String backend) {
        Fixture f = fixture(backend);
        boolean postgres = backend.equals("postgres");
        String prefix = scope() + "/";
        int count = 20_000;
        int projects = 200;
        Instant base = Instant.parse("2020-01-01T00:00:00Z");
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            String at = base.plusNanos(i).toString();
            batch.add(new Object[] {id, id, prefix + (i % projects), at, at});
        }
        new org.springframework.transaction.support.TransactionTemplate(
                        f.app().getBean(org.springframework.transaction.PlatformTransactionManager.class))
                .executeWithoutResult(ignored -> f.jdbc().batchUpdate("""
                        INSERT INTO agent_sessions (id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count)
                        VALUES (?, 'fixture', ?, 'Synthetic corpus session', ?, ?, ?, 1)
                        """, batch));
        try {
            // Exercise the one-time additive index build on an existing moderate corpus, not just an empty schema.
            f.jdbc().execute("DROP INDEX idx_agent_sessions_last_seen_instant");
            String ddl = "CREATE INDEX IF NOT EXISTS idx_agent_sessions_last_seen_instant ON agent_sessions ("
                    + SqlInstant.column("last_seen_at", postgres).indexColumns() + ")";
            long start = System.nanoTime();
            f.jdbc().execute(ddl);
            double buildMs = elapsedMs(start);
            f.jdbc().execute(ddl);
            if (postgres) f.jdbc().execute("ANALYZE agent_sessions");
            String sql = "SELECT id FROM agent_sessions WHERE spawned_by IS NULL ORDER BY "
                    + SqlInstant.column("last_seen_at", postgres).descending("id") + " LIMIT 25";
            String plan = f.jdbc()
                    .queryForList((postgres ? "EXPLAIN " : "EXPLAIN QUERY PLAN ") + sql)
                    .toString();
            assertThat(plan).contains("idx_agent_sessions_last_seen_instant");
            if (!postgres) assertThat(plan).doesNotContain("TEMP B-TREE");
            start = System.nanoTime();
            get(f, "/api/sessions?limit=25");
            double headMs = elapsedMs(start);
            start = System.nanoTime();
            List<JsonNode> summary = nodes(get(f, "/api/projects")).stream()
                    .filter(p -> p.path("canonicalKey").asText().startsWith(prefix))
                    .toList();
            double summaryMs = elapsedMs(start);
            assertThat(summary).hasSize(projects);
            for (JsonNode project : summary) {
                int offset =
                        Integer.parseInt(project.path("canonicalKey").asText().substring(prefix.length()));
                assertThat(project.path("sessionCount").asInt()).isEqualTo(count / projects);
                assertThat(project.path("eventCount").asInt()).isEqualTo(count / projects);
                assertThat(project.path("firstSeenAt").asText())
                        .isEqualTo(base.plusNanos(offset).toString());
                assertThat(project.path("lastSeenAt").asText())
                        .isEqualTo(base.plusNanos(count - projects + offset).toString());
            }
            System.out.printf(
                    "SESSION_CHRONOLOGY %s rows=%d projects=%d indexBuildMs=%.2f headHttpMs=%.2f projectsHttpMs=%.2f plan=%s%n",
                    backend, count, projects, buildMs, headMs, summaryMs, plan);
        } finally {
            f.jdbc().update("DELETE FROM agent_sessions WHERE cwd LIKE ?", prefix + "%");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgres"})
    void projectHumanSessionsFilterBeforeLimitAndRetainFirstTurnAndAliases(String backend) {
        Fixture f = fixture(backend);
        String repo = scope();
        String alias = scope();
        Seed first = capture(f, repo, UUID.randomUUID().toString(), "2026-10-03T11:00:00Z", false);
        Seed second = capture(f, alias, UUID.randomUUID().toString(), "2026-10-03T11:00:01Z", false);
        http.put(f.base() + "/api/project-aliases", Map.of("aliasKey", alias, "canonicalKey", repo));
        String machineId = "";
        for (int i = 0; i < 5; i++) {
            machineId = post(
                            f,
                            "/api/events",
                            Map.of(
                                    "source",
                                    "codex",
                                    "clientSessionId",
                                    UUID.randomUUID().toString(),
                                    "cwd",
                                    repo,
                                    "eventType",
                                    "PostToolUse",
                                    "toolName",
                                    "Read",
                                    "text",
                                    "Machine-only fixture",
                                    "observedAt",
                                    "2026-10-03T12:00:0" + i + "Z"))
                    .path("sessionId")
                    .asText();
        }
        capture(f, scope(), UUID.randomUUID().toString(), "2026-10-03T13:00:00Z", false);
        String key = project(f, repo).path("projectKey").asText();
        String path = "/api/projects/" + key + "/sessions?limit=2";
        assertThat(ids(get(f, path))).hasSize(2).doesNotContain(first.id(), second.id());
        assertThat(ids(get(f, path + "&humanOnly=false"))).isEqualTo(ids(get(f, path)));
        JsonNode filtered = get(f, path + "&humanOnly=true");
        assertThat(ids(filtered)).containsExactly(second.id(), first.id());
        for (JsonNode session : filtered) {
            assertThat(session.path("firstHumanTurn").asText()).isEqualTo("Synthetic chronology prompt");
        }
        assertThat(get(f, "/api/sessions/" + machineId).path("id").asText()).isEqualTo(machineId);
        assertThat(f.app()
                        .getBean(ProjectCatalogStore.class)
                        .sessionsForProjectByIds(repo, List.of(first.id()))
                        .get(0)
                        .firstHumanTurn())
                .isEqualTo("Synthetic chronology prompt");
    }

    private static double elapsedMs(long start) {

        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private Fixture fixture(String backend) {
        assumeTrue(fixtures.containsKey(backend), "PostgreSQL requires explicit SBA_POSTGRES_TEST_URL");

        return fixtures.get(backend);
    }

    private Seed capture(Fixture f, String repo, String client, String at, boolean keyed) {
        var event = Map.of(
                "source",
                "codex",
                "clientSessionId",
                client,
                "cwd",
                repo,
                "eventType",
                "UserPromptSubmit",
                "role",
                "user",
                "text",
                "Synthetic chronology prompt",
                "observedAt",
                at);
        JsonNode receipt = post(
                f,
                keyed ? "/api/events/idempotent" : "/api/events",
                keyed ? Map.of("captureId", UUID.randomUUID().toString(), "event", event) : event);

        return new Seed(receipt.path("sessionId").asText(), Instant.parse(at));
    }

    private JsonNode post(Fixture f, String path, Object body) {
        var response = http.postForEntity(f.base() + path, body, JsonNode.class);
        assertThat(response.getStatusCode().value())
                .as(path + ": " + response.getBody())
                .isEqualTo(200);

        return response.getBody();
    }

    private JsonNode get(Fixture f, String path) {
        var response = http.getForEntity(f.base() + path, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        return response.getBody();
    }

    private JsonNode project(Fixture f, String repo) {

        return nodes(get(f, "/api/projects")).stream()
                .filter(p -> repo.equals(p.path("canonicalKey").asText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<JsonNode> nodes(JsonNode array) {

        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private static List<String> ids(JsonNode array) {

        return nodes(array).stream().map(p -> p.path("id").asText()).toList();
    }

    private static List<String> idsForRepo(JsonNode array, String repo) {

        return nodes(array).stream()
                .filter(p -> repo.equals(p.path("cwd").asText()))
                .map(p -> p.path("id").asText())
                .toList();
    }

    private static String scope() {

        return "/fixture/session-chronology/" + UUID.randomUUID();
    }

    private static String postgresUsername() {

        return System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test");
    }

    private static String postgresPassword() {

        return System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test");
    }

    private static Connection postgresConnection() throws Exception {

        return DriverManager.getConnection(
                System.getenv("SBA_POSTGRES_TEST_URL"), postgresUsername(), postgresPassword());
    }

    private record Fixture(ServletWebServerApplicationContext app, JdbcTemplate jdbc, String base) {}

    private record Seed(String id, Instant at) {}
}
