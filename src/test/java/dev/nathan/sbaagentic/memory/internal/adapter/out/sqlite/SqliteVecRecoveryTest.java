package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore.ScoredKey;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SqliteVecRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void enablingNativeSearchRecoversAlreadyStoredEmbeddings() throws Exception {
        Path extension = Path.of(System.getenv()
                .getOrDefault(
                        "SBA_SQLITE_VEC_PATH",
                        "/opt/homebrew/lib/node_modules/openclaw/node_modules/sqlite-vec-darwin-arm64/vec0.dylib"));
        Assumptions.assumeTrue(Files.isRegularFile(extension), "sqlite-vec native extension unavailable");
        Properties properties = new Properties();
        properties.setProperty("enable_load_extension", "true");
        try (var connection =
                DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("restore.db"), properties)) {
            var source = new SingleConnectionDataSource(connection, true);
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            EmbeddingSqlStore canonical = new EmbeddingSqlStore(jdbc);
            canonical.upsert(stored("one", "current", new float[] {1, 0}));
            canonical.upsert(stored("other", "other-model", new float[] {1, 0}));
            byte[] before =
                    jdbc.queryForObject("SELECT vector FROM memory_embeddings WHERE target_id='one'", byte[].class);
            var vectorSettings = new MemoryVectorProperties();
            vectorSettings.setSqliteVecPath(extension.toString());
            var embeddingSettings = new MemoryEmbeddingProperties();
            embeddingSettings.setModel("current");
            embeddingSettings.setDimensions(2);
            var bruteForce = new BruteForceVectorStore(jdbc);
            var vectors = new SqliteVecVectorStore(jdbc, source, vectorSettings, embeddingSettings, bruteForce);
            vectors.initialize();
            assertThat(vectors.available()).isTrue();
            var query = new EmbeddingVector("current", new float[] {1, 0});
            assertThat(vectors.knn(query, 10, key -> true))
                    .extracting(ScoredKey::key)
                    .containsExactly("event:one");
            assertThat(jdbc.queryForObject("SELECT vector FROM memory_embeddings WHERE target_id='one'", byte[].class))
                    .isEqualTo(before);

            // Simulate canonical writes surviving an interrupted accelerator update, then restart.
            canonical.deleteFor("event", "one");
            canonical.upsert(stored("two", "current", new float[] {0, 1}));
            vectors = new SqliteVecVectorStore(jdbc, source, vectorSettings, embeddingSettings, bruteForce);
            vectors.initialize();
            assertThat(vectors.available()).isTrue();
            assertThat(vectors.knn(query, 10, key -> true))
                    .extracting(ScoredKey::key)
                    .containsExactly("event:two");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_vec", Integer.class))
                    .isEqualTo(1);

            // A different model uses its canonical corpus, never a same-dimensional foreign index.
            assertThat(vectors.knn(new EmbeddingVector("other-model", new float[] {1, 0}), 10, key -> true))
                    .extracting(ScoredKey::key)
                    .containsExactly("event:other");
            canonical.upsert(stored("two", "other-model", new float[] {1, 0}));
            vectors.upsert(stored("two", "other-model", new float[] {1, 0}));
            assertThat(vectors.available()).isTrue();
            assertThat(vectors.knn(query, 10, key -> true)).isEmpty();
            assertThat(canonical.count()).isEqualTo(2);

            // A dimensions change cannot reuse the old native table. Failed rebuild stays on
            // canonical ranking and leaves the recorded vectors untouched.
            var sentinel = stored("rollback-sentinel", "current", new float[] {1, 0});
            canonical.upsert(sentinel);
            vectors.upsert(sentinel);
            var nativeBefore = jdbc.queryForList("SELECT key, hex(embedding) AS vector FROM memory_vec ORDER BY key");
            assertThat(nativeBefore).hasSize(1);
            canonical.upsert(stored("three", "current", new float[] {0, 0, 1}));
            var canonicalBefore = jdbc.queryForList("""
                    SELECT target_kind, target_id, model, dimensions, hex(vector) AS vector, content_hash, embedded_at
                    FROM memory_embeddings ORDER BY target_kind, target_id
                    """);
            embeddingSettings.setDimensions(3);
            vectors = new SqliteVecVectorStore(jdbc, source, vectorSettings, embeddingSettings, bruteForce);
            vectors.initialize();
            assertThat(vectors.available()).isFalse();
            assertThat(vectors.knn(new EmbeddingVector("current", new float[] {0, 0, 1}), 10, key -> true))
                    .extracting(ScoredKey::key)
                    .containsExactly("event:three");
            assertThat(canonical.count()).isEqualTo(4);
            assertThat(jdbc.queryForList("SELECT key, hex(embedding) AS vector FROM memory_vec ORDER BY key"))
                    .isEqualTo(nativeBefore);
            assertThat(jdbc.queryForList("""
                    SELECT target_kind, target_id, model, dimensions, hex(vector) AS vector, content_hash, embedded_at
                    FROM memory_embeddings ORDER BY target_kind, target_id
                    """)).isEqualTo(canonicalBefore);
        }
    }

    private static StoredEmbedding stored(String id, String model, float[] vector) {

        return new StoredEmbedding(
                "event",
                id,
                new EmbeddingVector(model, vector),
                "hash-" + id,
                Instant.parse("2026-10-02T12:00:00.123456789Z"));
    }
}
