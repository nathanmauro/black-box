package dev.nathan.sbaagentic.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;

import dev.nathan.sbaagentic.SbaAgenticApplication;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore;
import dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite.BruteForceVectorStore;
import dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite.SqliteVecVectorStore;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectCatalogStore;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.workflow.CompleteTaskRequest;
import dev.nathan.sbaagentic.workflow.TaskQuery;
import dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite.TaskRepository;
import dev.nathan.sbaagentic.workflow.internal.application.TaskService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real engine and HTTP contracts. Each run creates/drops only its own randomly named schema. */
@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresBackendContractTest {
    private final String schema = "bb_contract_" + UUID.randomUUID().toString().replace("-", "");
    private final String repo = "/postgres-contract/" + UUID.randomUUID();
    private final TestRestTemplate http = new TestRestTemplate();
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private String base;

    @BeforeAll
    void startIsolatedSchema() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        startApp();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(System.getenv("SBA_POSTGRES_TEST_URL"),
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test"),
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"));
    }

    private void startApp() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class).run(
                "--spring.profiles.active=postgres",
                "--spring.datasource.url=" + System.getenv("SBA_POSTGRES_TEST_URL"),
                "--spring.datasource.username=" + System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test"),
                "--spring.datasource.password=" + System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"),
                "--spring.datasource.hikari.data-source-properties.currentSchema=" + schema,
                "--server.address=127.0.0.1", "--server.port=0", "--sba.editor.enabled=false",
                "--sba.local-ai.enabled=false", "--sba.summary.backend=local",
                "--sba.elasticsearch.enabled=false", "--sba.memory.embedding.enabled=false",
                "--sba.ask.embedding-enabled=false", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    void removeOnlyOurSchema() throws Exception {
        if (app != null) app.close();
        try (Connection connection = connection()) {
            connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void httpCaptureRecallFeedProjectsAndRestartPreserveData() {
        String session = "capture-" + UUID.randomUUID();
        JsonNode saved = post("/api/decisions", Map.of("source", "codex", "clientSessionId", session,
                "repo", repo, "decision", "Preserve exact evidence over database changes",
                "rationale", "The same agent contract must work locally and remotely.",
                "alternatives", List.of("duplicate repositories"), "confidence", 0.9,
                "openLoops", List.of("measure retrieval quality")));
        String id = saved.path("eventId").asText();
        assertThat(id).isNotBlank();
        JsonNode recall = get("/api/recall?scope=" + id);
        assertThat(recall.path("mode").asText()).isEqualTo("lexical");
        assertThat(recall.path("items").get(0).path("eventId").asText()).isEqualTo(id);
        assertThat(get("/api/events?query=evidence").path("items").size()).isPositive();
        assertThat(get("/api/events/facets").path("total").asLong()).isPositive();
        assertThat(get("/api/projects").toString()).contains(repo);
        assertThat(get("/api/stats").toString()).contains("events");
        app.close();
        startApp();
        assertThat(get("/api/recall?scope=" + id).path("items").get(0).path("eventId").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_sessions WHERE client_session_id = ?", Long.class, session)).isEqualTo(1);
        HikariDataSource pool = app.getBean(HikariDataSource.class);
        assertThat(pool.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(pool.getDataSourceProperties()).doesNotContainKeys("foreign_keys", "busy_timeout", "enable_load_extension");
        assertThat(app.getBeansOfType(SqliteVecVectorStore.class)).isEmpty();
        assertThat(app.getBean(MemoryVectorStore.class)).isInstanceOf(BruteForceVectorStore.class);
    }

    @Test
    void projectQueriesRetainNanosecondTextAndMixedCaseMetadata() {
        String scope = repo + "/time";
        List<String> stamps = List.of("2026-09-08T12:00:00.120000001Z", "2026-09-08T12:00:00.120Z", "2026-09-08T12:00:00Z");
        for (String stamp : stamps) {
            post("/api/events", Map.of("source", "codex", "clientSessionId", "time-" + stamp,
                    "cwd", scope, "eventType", "Notification", "text", "timestamp " + stamp,
                    "metadata", Map.of("KiNd", "DeCiSiOn"), "observedAt", stamp));
        }
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode timeline = get("/api/projects/" + key + "/timeline");
        assertThat(timeline.path("count").asLong()).isEqualTo(3);
        var blocks = app.getBean(ProjectCatalogStore.class).timelineBlocks(scope, 10, 0);
        assertThat(blocks).hasSize(3);
        assertThat(blocks.stream().map(block -> block.observedAt().toString()).toList())
                .containsExactly(stamps.get(2), stamps.get(1), stamps.get(0));
        assertThat(get("/api/projects/" + key + "/graph").path("captures").size()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT observed_at FROM agent_events WHERE session_id IN (SELECT id FROM agent_sessions WHERE cwd = ?)", String.class, scope))
                .containsExactlyInAnyOrderElementsOf(stamps);
    }

    @Test
    void claimersCannotShareOneTaskAndCanConsumeDifferentTasks() throws Exception {
        TaskRepository tasks = app.getBean(TaskRepository.class);
        var spec = tasks.createSpec(repo, "Claim concurrency", "A frozen contract", null, "test");
        String lane = "claim-" + UUID.randomUUID();
        tasks.enqueueTask(spec.id(), "one winner", lane, 1, "test");
        try (var workers = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            var first = workers.submit(() -> { barrier.await(); return tasks.claimNextTask(lane, "a"); });
            var second = workers.submit(() -> { barrier.await(); return tasks.claimNextTask(lane, "b"); });
            var claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        for (int i = 0; i < 8; i++) tasks.enqueueTask(spec.id(), "queue-" + i, lane, i, "test");
        try (var workers = Executors.newFixedThreadPool(8)) {
            CyclicBarrier barrier = new CyclicBarrier(8);
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                String actor = "worker-" + i;
                results.add(workers.submit(() -> { barrier.await(); return tasks.claimNextTask(lane, actor).orElseThrow().snapshot().task().id(); }));
            }
            var ids = new ArrayList<String>();
            for (var result : results) ids.add(result.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(8).doesNotHaveDuplicates();
        }
        assertThat(tasks.listTasks(new TaskQuery(repo, lane, null, List.of(), null, 1))).hasSize(8);
    }

    @Test
    void httpTaskLifecycleCompletesIntoRecallableHandoff() {
        JsonNode spec = post("/api/specs", Map.of("projectKey", repo, "title", "HTTP lifecycle", "body", "Do the bounded work", "actor", "test"));
        String lane = "http-" + UUID.randomUUID();
        JsonNode queued = post("/api/tasks", Map.of("specId", spec.path("id").asText(), "title", "Implement", "lane", lane, "priority", 1, "actor", "test"));
        String taskId = queued.path("snapshot").path("task").path("id").asText();
        post("/api/tasks/claim", Map.of("lane", lane, "agent", "worker"));
        var blocked = http.exchange(base + "/api/tasks/" + taskId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("actor", "worker", "status", "blocked", "blockedReason", "Fixture wait")), JsonNode.class);
        assertThat(blocked.getStatusCode().is2xxSuccessful()).isTrue();
        var reset = http.exchange(base + "/api/tasks/" + taskId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("actor", "worker", "status", "open")), JsonNode.class);
        assertThat(reset.getStatusCode().is2xxSuccessful()).isTrue();
        post("/api/tasks/claim", Map.of("lane", lane, "agent", "worker"));
        JsonNode done = post("/api/tasks/" + taskId + "/complete", Map.of("actor", "worker", "source", "codex",
                "clientSessionId", "complete-" + UUID.randomUUID(), "summary", "Verified cloud database contract", "openLoops", List.of(), "nextAction", "Continue"));
        String handoff = done.path("snapshot").path("task").path("resultHandoffId").asText();
        assertThat(done.path("snapshot").path("task").path("status").asText()).isEqualTo("done");
        assertThat(get("/api/recall?scope=" + handoff).path("items").get(0).path("kind").asText()).isEqualTo("handoff");
    }

    @Test
    void failedCompletionRollsBackHandoffSessionEventCountAndTask() {
        TaskRepository tasks = app.getBean(TaskRepository.class);
        var spec = tasks.createSpec(repo, "Rollback", "Atomic completion", null, "test");
        String lane = "rollback-" + UUID.randomUUID();
        String id = tasks.enqueueTask(spec.id(), "rollback", lane, 0, "test").snapshot().task().id();
        tasks.claimNextTask(lane, "worker");
        long events = jdbc.queryForObject("SELECT count(*) FROM agent_events", Long.class);
        long sessions = jdbc.queryForObject("SELECT count(*) FROM agent_sessions", Long.class);
        jdbc.execute("CREATE FUNCTION reject_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type = 'task.completed' THEN RAISE EXCEPTION 'forced completion failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_completion BEFORE INSERT ON task_events FOR EACH ROW EXECUTE FUNCTION reject_completion()");
        try {
            assertThatThrownBy(() -> app.getBean(TaskService.class).completeTask(new CompleteTaskRequest(id, "worker", "codex", "rolled-back-session", "Must roll back", List.of(), "Retry")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, id)).isEqualTo("in_progress");
            assertThat(jdbc.queryForObject("SELECT result_handoff_id FROM tasks WHERE id = ?", String.class, id)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events", Long.class)).isEqualTo(events);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_sessions", Long.class)).isEqualTo(sessions);
        }
        finally {
            jdbc.execute("DROP TRIGGER reject_completion ON task_events");
            jdbc.execute("DROP FUNCTION reject_completion()");
        }
    }

    @Test
    void aliasesSavedMeldsAndLineageUseTheSameStoredEvidence() {
        String root = repo + "/catalog";
        String alias = root + "/worktree";
        String session = "catalog-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("source", "codex", "clientSessionId", session,
                "cwd", root, "eventType", "Decision", "text", "MY_SECRET_KEY=abcd1234efgh",
                "metadata", Map.of("kind", "decision"));
        JsonNode first = post("/api/events", payload);
        post("/api/events", payload);
        JsonNode child = post("/api/events", Map.of("source", "claude", "clientSessionId", "child-" + UUID.randomUUID(),
                "cwd", alias, "eventType", "Handoff", "text", "A related result"));
        String parentId = first.path("sessionId").asText();
        String childId = child.path("sessionId").asText();
        assertThat(get("/api/events/" + first.path("eventId").asText()).path("text").asText()).contains("[REDACTED]").doesNotContain("abcd1234efgh");
        assertThat(jdbc.queryForObject("SELECT event_count FROM agent_sessions WHERE id = ?", Integer.class, parentId)).isEqualTo(2);
        app.getBean(RecordingCatalog.class).saveSummaryAndTitle(parentId, "Saved summary", "Better title", 100);
        assertThat(get("/api/sessions/" + parentId).path("summary").asText()).isEqualTo("Saved summary");
        var merged = http.exchange(base + "/api/project-aliases", HttpMethod.PUT,
                new HttpEntity<>(Map.of("aliasKey", alias, "canonicalKey", root)), JsonNode.class);
        assertThat(merged.getStatusCode().is2xxSuccessful()).as(String.valueOf(merged.getBody())).isTrue();
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(root.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode meld = post("/api/melds", Map.of("projectKey", key, "title", "Catalog synthesis", "body", "Both sessions contributed",
                "sessionIds", List.of(parentId, childId), "savedFromPreview", true));
        assertThat(get("/api/projects/" + key + "/melds").toString()).contains(meld.path("id").asText());
        assertThat(get("/api/projects/" + key + "/timeline").path("count").asLong()).isEqualTo(4);
        assertThat(get("/api/projects/" + key + "/graph").path("captures").size()).isEqualTo(4);
        Map<String, String> link = Map.of("parentSessionId", parentId, "childSessionId", childId, "linkType", "spawned");
        post("/api/session-links", link);
        assertThat(get("/api/sessions/" + parentId + "/links").toString()).contains(childId);
        var duplicate = http.postForEntity(base + "/api/session-links", link, JsonNode.class);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(get("/api/session-links/child-counts?ids=" + parentId).path(parentId).asInt()).isEqualTo(1);
        var deleted = http.exchange(base + "/api/project-aliases?aliasKey=" + alias, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void failedClaimHistoryInsertRollsBackOwnership() {
        TaskRepository tasks = app.getBean(TaskRepository.class);
        var spec = tasks.createSpec(repo, "Claim rollback", "Atomic ownership", null, "test");
        String lane = "failed-claim-" + UUID.randomUUID();
        String id = tasks.enqueueTask(spec.id(), "rollback claim", lane, 0, "test").snapshot().task().id();
        jdbc.execute("CREATE FUNCTION reject_claim() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type = 'task.claimed' THEN RAISE EXCEPTION 'forced claim failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_claim BEFORE INSERT ON task_events FOR EACH ROW EXECUTE FUNCTION reject_claim()");
        try {
            assertThatThrownBy(() -> tasks.claimNextTask(lane, "worker")).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, id)).isEqualTo("open");
            assertThat(jdbc.queryForObject("SELECT claimed_by FROM tasks WHERE id = ?", String.class, id)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM task_events WHERE task_id = ?", Long.class, id)).isEqualTo(1);
        }
        finally {
            jdbc.execute("DROP TRIGGER reject_claim ON task_events");
            jdbc.execute("DROP FUNCTION reject_claim()");
        }
    }

    @Test
    void canonicalEmbeddingUpsertAndJavaRankingNeedNoExtension() {
        EmbeddingStore store = app.getBean(EmbeddingStore.class);
        MemoryVectorStore vectors = app.getBean(MemoryVectorStore.class);
        store.upsert(new EmbeddingStore.StoredEmbedding("event", "vector-fixture", new EmbeddingVector("fixture-model", new float[]{1, 0, 0}), "old", Instant.now()));
        store.upsert(new EmbeddingStore.StoredEmbedding("event", "vector-fixture", new EmbeddingVector("fixture-model", new float[]{0, 1, 0}), "new", Instant.parse("2026-09-08T12:00:00.123456789Z")));
        assertThat(store.findHash("event", "vector-fixture")).contains("new");
        assertThat(store.loadAll("fixture-model", 3)).singleElement().satisfies(row -> {
            assertThat(row.vector().values()).containsExactly(0, 1, 0);
            assertThat(row.embeddedAt()).isEqualTo(Instant.parse("2026-09-08T12:00:00.123456789Z"));
        });
        assertThat(vectors.knn(new EmbeddingVector("fixture-model", new float[]{0, 1, 0}), 3, key -> true)).hasSize(1);
        assertThat(vectors.knn(new EmbeddingVector("other-model", new float[]{0, 1, 0}), 3, key -> true)).isEmpty();
        store.deleteFor("event", "vector-fixture");
        assertThat(store.findHash("event", "vector-fixture")).isEmpty();
    }

    private JsonNode get(String path) {
        var response = http.getForEntity(base + path, JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + " " + response.getBody()).isTrue();
        return response.getBody();
    }

    private JsonNode post(String path, Object body) {
        var response = http.postForEntity(base + path, body, JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + " " + response.getBody()).isTrue();
        return response.getBody();
    }
}
