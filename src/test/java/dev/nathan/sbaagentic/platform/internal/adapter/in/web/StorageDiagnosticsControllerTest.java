package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.platform.internal.adapter.out.filesystem.BackupManifestFixtures;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-storage-diagnostics-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.memory.embedding.model=" + StorageDiagnosticsControllerTest.MODEL,
            "sba.memory.embedding.dimensions=3",
            "sba.judge.enabled=false",
            // A credential-named setting: its value is masked everywhere and must never surface.
            "sba.judge.api-key=" + StorageDiagnosticsControllerTest.CONFIGURED_SECRET
        })
@AutoConfigureMockMvc
@DisplayName("GET /api/diagnostics/storage reports storage health read-only and sanitized")
class StorageDiagnosticsControllerTest {

    static final String MODEL = "diagnostics-stub";
    static final String CONFIGURED_SECRET = "Pw4nQ8zLx2Rt6Vb9Kd3Hs7Fm";
    private static final String CAPTURED_PASSWORD = "c4ptured-hunter2-Secret";
    private static final String REPO = "/fixture/storage-diagnostics";
    private static final Path BACKUPS = createBackupDir();

    @Autowired
    MockMvc http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingCaptureOperations captures;

    @DynamicPropertySource
    static void backupDirectory(DynamicPropertyRegistry registry) {
        registry.add("sba.diagnostics.backup-dir", BACKUPS::toString);
    }

    @AfterAll
    static void removeBackupDir() throws IOException {
        try (Stream<Path> files = Files.walk(BACKUPS)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @BeforeEach
    void seed() throws IOException {
        jdbc.update("DELETE FROM memory_embeddings");
        jdbc.update("DELETE FROM agent_events");
        jdbc.update("DELETE FROM agent_sessions");
        // Re-index from scratch: a previous test may have deliberately desynchronized FTS.
        jdbc.execute("INSERT INTO event_fts(event_fts) VALUES('delete-all')");
        try (Stream<Path> files = Files.list(BACKUPS)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }

        captures.captureDecision(new CaptureDecisionRequest(
                "codex",
                "diag-1",
                REPO,
                "Keep the diagnostics endpoint read-only",
                "Rotated the service login: password=" + CAPTURED_PASSWORD,
                List.of("expose a repair endpoint"),
                0.9,
                List.of()));
        captures.captureHandoff(new CaptureHandoffRequest(
                "claude", "diag-2", REPO, "codex", "Diagnostics shipped", List.of(), "Watch the WAL size"));
        captures.captureObservation("codex", "diag-1", REPO, "configured value " + CONFIGURED_SECRET + " seen");
        BackupManifestFixtures.write(BACKUPS, "blackbox-nightly", Instant.now().minus(2, ChronoUnit.HOURS));
    }

    @Test
    void reportsFootprintRowCountsIndexParityRedactionAndBackups() throws Exception {
        http.perform(get("/api/diagnostics/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt", notNullValue()))
                .andExpect(jsonPath("$.database.backend").value("sqlite"))
                .andExpect(jsonPath("$.database.fileName", endsWith(".db")))
                .andExpect(jsonPath("$.database.fileBytes", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.database.walBytes", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.database.shmBytes", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.database.journalMode", notNullValue()))
                .andExpect(jsonPath("$.database.pageSize", greaterThanOrEqualTo(512)))
                .andExpect(jsonPath("$.tableRows.agent_events").value(3))
                .andExpect(jsonPath("$.tableRows.agent_sessions").value(2))
                .andExpect(jsonPath("$.tableRows.memory_embeddings").value(3))
                .andExpect(jsonPath("$.indexes.fullText.status").value("in_sync"))
                .andExpect(jsonPath("$.indexes.fullText.eventRows").value(3))
                .andExpect(jsonPath("$.indexes.fullText.indexedRows").value(3))
                .andExpect(jsonPath("$.indexes.embeddings.status").value("in_sync"))
                .andExpect(jsonPath("$.indexes.embeddings.model").value(MODEL))
                .andExpect(jsonPath("$.indexes.embeddings.embeddableEvents").value(3))
                .andExpect(jsonPath("$.indexes.embeddings.embeddedEvents").value(3))
                .andExpect(jsonPath("$.indexes.embeddings.missingEvents").value(0))
                .andExpect(jsonPath("$.indexes.embeddings.vectorBackend").value("brute-force"))
                .andExpect(jsonPath("$.redaction.ingestionRedactionEnabled").value(true))
                .andExpect(jsonPath("$.redaction.configuredSecretValues", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.redaction.ingest.inspected", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.redaction.ingest.redacted", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.backups.status").value("ok"))
                .andExpect(jsonPath("$.backups.checksumsVerified").value(false))
                .andExpect(jsonPath("$.backups.manifests[0].label").value("blackbox-nightly"))
                .andExpect(jsonPath("$.backups.manifests[0].sealed").value(true))
                .andExpect(jsonPath("$.backups.manifests[0].authentication").value("not_verified"))
                .andExpect(jsonPath("$.backups.manifests[0].archiveChecksumMatches")
                        .doesNotExist());
    }

    @Test
    void checksumVerificationIsOptIn() throws Exception {
        http.perform(get("/api/diagnostics/storage").param("verifyChecksums", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backups.checksumsVerified").value(true))
                .andExpect(jsonPath("$.backups.manifests[0].archiveChecksumMatches")
                        .value(true));
    }

    @Test
    void detectsFullTextDriftAndMissingEmbeddings() throws Exception {
        Long rowid = jdbc.queryForObject("SELECT min(rowid) FROM agent_events", Long.class);
        jdbc.update("DELETE FROM event_fts WHERE rowid = ?", rowid);
        jdbc.update("DELETE FROM memory_embeddings WHERE rowid = (SELECT min(rowid) FROM memory_embeddings)");

        http.perform(get("/api/diagnostics/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexes.fullText.status").value("drift"))
                .andExpect(jsonPath("$.indexes.fullText.indexedRows").value(2))
                .andExpect(jsonPath("$.indexes.embeddings.status").value("embedding_disabled"))
                .andExpect(jsonPath("$.indexes.embeddings.missingEvents").value(1));
    }

    @Test
    void detectsOrphanedAndOtherModelEmbeddings() throws Exception {
        jdbc.update("""
            INSERT INTO memory_embeddings (target_kind, target_id, model, dimensions, vector, content_hash, embedded_at)
            VALUES ('event', 'gone-event', ?, 3, x'000000000000000000000000', 'h', '2026-11-19T00:00:00Z'),
                   ('session_summary', 'gone-session', 'old-model', 768, x'00', 'h', '2026-11-19T00:00:00Z')
            """, MODEL);

        http.perform(get("/api/diagnostics/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexes.embeddings.orphanedEmbeddings").value(2))
                .andExpect(jsonPath("$.indexes.embeddings.otherModelEmbeddings").value(1))
                .andExpect(jsonPath("$.indexes.embeddings.embeddedEvents").value(3));
    }

    @Test
    void requestLeavesDatabaseAndBackupFilesUntouched() throws Exception {
        List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        List<Map<String, Object>> embeddings =
                jdbc.queryForList("SELECT target_kind, target_id, model FROM memory_embeddings ORDER BY 1, 2");
        Map<Path, FileTime> backups = backupTimes();

        http.perform(get("/api/diagnostics/storage").param("verifyChecksums", "true"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT * FROM agent_events ORDER BY id")).isEqualTo(events);
        assertThat(jdbc.queryForList("SELECT target_kind, target_id, model FROM memory_embeddings ORDER BY 1, 2"))
                .isEqualTo(embeddings);
        assertThat(backupTimes()).isEqualTo(backups);
    }

    @Test
    void writeMethodsAreNotRouted() throws Exception {
        http.perform(post("/api/diagnostics/storage")).andExpect(status().is4xxClientError());
        http.perform(put("/api/diagnostics/storage")).andExpect(status().is4xxClientError());
        http.perform(delete("/api/diagnostics/storage")).andExpect(status().is4xxClientError());
    }

    @Test
    void responseCarriesNoSecretValuePathOrManifestKeyMaterial() throws Exception {
        String body = http.perform(get("/api/diagnostics/storage").param("verifyChecksums", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(CONFIGURED_SECRET)
                .doesNotContain(CAPTURED_PASSWORD)
                .doesNotContain(BackupManifestFixtures.SALT)
                .doesNotContain(BackupManifestFixtures.MAC)
                .doesNotContain(BACKUPS.toString())
                .doesNotContain(Path.of(System.getProperty("java.io.tmpdir")).toString())
                .doesNotContain(System.getProperty("user.home"))
                .doesNotContainPattern("[0-9a-f]{64}");
    }

    private static Path createBackupDir() {
        try {

            return Files.createTempDirectory("bb-diagnostics-backups");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Map<Path, FileTime> backupTimes() throws IOException {
        Map<Path, FileTime> times = new HashMap<>();
        try (Stream<Path> files = Files.list(BACKUPS)) {
            for (Path file : files.toList()) {
                times.put(file, Files.getLastModifiedTime(file));
            }
        }

        return times;
    }

    @TestConfiguration
    static class StubEmbedderConfig {

        @Bean
        @Primary
        TextEmbedder diagnosticsStubEmbedder() {

            return new TextEmbedder() {
                @Override
                public EmbeddingVector embedDocument(String text) {

                    return new EmbeddingVector(MODEL, new float[] {1.0f, 0.0f, 0.0f});
                }

                @Override
                public EmbeddingVector embedQuery(String text) {

                    return embedDocument(text);
                }

                @Override
                public String documentContentHash(String text) {

                    return EmbeddingVector.contentHash(text);
                }

                @Override
                public boolean available() {

                    return true;
                }

                @Override
                public String model() {

                    return MODEL;
                }

                @Override
                public int dimensions() {

                    return 3;
                }
            };
        }
    }
}
