package dev.nathan.sbaagentic.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-lineage-http-${random.uuid}.db",
            "server.address=127.0.0.1",
            "sba.editor.enabled=false",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.summary.backend=local"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LineageMetadataHttpTest {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 500})
    void linksAndDagKeepOrderedMetadataOverRealHttp(int peers) throws Exception {
        String center = "center-" + peers;
        String summary = "large-summary-marker ".repeat(2048);
        List<Object[]> sessions = new ArrayList<>();
        List<Object[]> links = new ArrayList<>();
        List<String> peerIds = new ArrayList<>();
        sessions.add(new Object[] {center, "claude", center, "Center", summary});
        for (int index = 0; index < peers; index++) {
            String id = center + "-peer-" + index;
            peerIds.add(id);
            sessions.add(new Object[] {id, "codex", id, index == 0 ? "" : "Title " + index, summary});
            links.add(new Object[] {
                center + "-link-" + index,
                center,
                id,
                Instant.parse("2026-11-10T12:00:00Z").plusSeconds(index).toString()
            });
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.batchUpdate("""
                INSERT INTO agent_sessions
                    (id, source, client_session_id, title, summary, started_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, '2026-11-10T12:00:00Z', '2026-11-10T12:00:00Z')
                """, sessions);
            jdbc.batchUpdate("""
                INSERT INTO session_links (id, parent_session_id, child_session_id, link_type, created_at)
                VALUES (?, ?, ?, 'spawned', ?)
                """, links);
        });

        JsonNode linkResponse = get("/api/sessions/" + center + "/links");
        JsonNode dag = get("/api/dag?sessionId=" + center);

        assertThat(linkResponse.path("parents")).isEmpty();
        assertThat(linkResponse.path("children").findValuesAsText("childSessionId"))
                .containsExactlyElementsOf(peerIds);
        for (int index = 0; index < peers; index++) {
            JsonNode session = linkResponse.path("children").get(index).path("session");
            assertThat(session.path("id").asText()).isEqualTo(peerIds.get(index));
            assertThat(session.path("source").asText()).isEqualTo("codex");
            assertThat(session.path("title").asText()).isEqualTo(index == 0 ? "" : "Title " + index);
        }
        List<String> nodeIds = new ArrayList<>();
        nodeIds.add(center);
        nodeIds.addAll(peerIds);
        assertThat(dag.path("nodes").findValuesAsText("ref")).containsExactlyElementsOf(nodeIds);
        assertThat(dag.path("nodes").get(0).path("label").asText()).isEqualTo("Center");
        assertThat(dag.path("nodes").get(1).path("label").asText()).isEmpty();
        assertThat(dag.path("edges")).hasSize(peers);
        System.out.printf("lineage-http peers=%d links=%d nodes=%d edges=%d%n", peers, peers, peers + 1, peers);
    }

    private JsonNode get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("summary", "large-summary-marker");

        return mapper.readTree(response.body());
    }
}
