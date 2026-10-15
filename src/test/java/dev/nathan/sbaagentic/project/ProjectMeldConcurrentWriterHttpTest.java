package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite admits one writer at a time, and agents keep writing while a person saves a braid: a
 * capture, or the summary that a Stop event schedules. A save reads its sessions before it
 * inserts, so it has to wait for that writer to commit instead of failing the request.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMeldConcurrentWriterHttpTest {
    private static final Duration WRITER_HOLD = Duration.ofMillis(600);

    @TempDir
    static Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private String base;

    @BeforeAll
    void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + database(),
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
                        "--logging.level.root=WARN");
        mapper = app.getBean(ObjectMapper.class);
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
        client.close();
    }

    @Test
    void braidSaveWaitsForAConcurrentWriterInsteadOfFailing() throws Exception {
        List<String> sessions = List.of(
                seed("/fixture/contended-a-" + UUID.randomUUID())
                        .path("sessionId")
                        .asText(),
                seed("/fixture/contended-b-" + UUID.randomUUID())
                        .path("sessionId")
                        .asText());
        int melds = count("session_melds");

        HttpResponse<String> response;
        long answeredAt;
        long writerCommitStartedAt;
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + database());
                Statement transaction = writer.createStatement();
                PreparedStatement summary =
                        writer.prepareStatement("UPDATE agent_sessions SET summary = ? WHERE id = ?")) {
            transaction.execute("BEGIN IMMEDIATE");
            summary.setString(1, "Summary written while the braid was saved");
            summary.setString(2, sessions.get(0));
            assertThat(summary.executeUpdate()).isEqualTo(1);
            CompletableFuture<Long> committed = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(WRITER_HOLD.toMillis());
                    long releasing = System.nanoTime();
                    transaction.execute("COMMIT");

                    return releasing;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });

            response = send("POST", "/api/melds", braid(sessions));
            answeredAt = System.nanoTime();
            writerCommitStartedAt = committed.get(10, TimeUnit.SECONDS);
        }

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(answeredAt)
                .as("the save waits for the other writer's commit")
                .isGreaterThan(writerCommitStartedAt);
        String id = mapper.readTree(response.body()).path("id").asText();
        assertThat(count("session_melds")).isEqualTo(melds + 1);
        assertThat(jdbc.queryForList(
                        "SELECT session_id FROM session_meld_inputs WHERE meld_id=? ORDER BY input_order",
                        String.class,
                        id))
                .containsExactlyElementsOf(sessions);
        // The save read its sessions after the other writer committed, so it saw that summary.
        assertThat(jdbc.queryForList(
                        "SELECT included_summary FROM session_meld_inputs WHERE meld_id=? ORDER BY input_order",
                        Integer.class,
                        id))
                .containsExactly(1, 0);
        assertThat(jdbc.queryForObject("SELECT summary FROM agent_sessions WHERE id=?", String.class, sessions.get(0)))
                .isEqualTo("Summary written while the braid was saved");
    }

    private static Path database() {

        return tempDir.resolve("contention.db");
    }

    private JsonNode seed(String project) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/api/events",
                Map.of(
                        "source",
                        "manual",
                        "clientSessionId",
                        UUID.randomUUID().toString(),
                        "cwd",
                        project,
                        "eventType",
                        "Observation",
                        "text",
                        "Original project evidence"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        return mapper.readTree(response.body());
    }

    private Map<String, Object> braid(List<String> sessions) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "Braid saved during a capture");
        request.put("body", "A saved artifact, without a model call");
        request.put("executionMode", "export_bundle");
        request.put("sessionIds", sessions);
        request.put("metadata", Map.of("kind", "braid"));

        return request;
    }

    private int count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
