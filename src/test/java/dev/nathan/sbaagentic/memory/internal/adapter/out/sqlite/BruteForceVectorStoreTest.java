package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore.ScoredKey;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class BruteForceVectorStoreTest {

    @Test
    void ranksByCosineSimilarity() {
        Fixture fixture = fixture();
        fixture.embeddingStore().upsert(stored("event", "close", new float[] { 1.0f, 0.0f }));
        fixture.embeddingStore().upsert(stored("event", "near", new float[] { 0.9f, 0.1f }));
        fixture.embeddingStore().upsert(stored("event", "far", new float[] { 0.0f, 1.0f }));

        assertThat(fixture.vectorStore().knn(query(1.0f, 0.0f), 2, key -> true))
                .extracting(ScoredKey::key)
                .containsExactly("event:close", "event:near");
    }

    @Test
    void normalizingInputsDoesNotChangeCosineRanking() {
        Fixture rawFixture = fixture();
        insertCanonical(rawFixture.jdbcTemplate(), "event", "far_aligned", new float[] { 5.0f, 0.0f });
        insertCanonical(rawFixture.jdbcTemplate(), "event", "near_offaxis", new float[] { 0.9f, 0.6f });
        insertCanonical(rawFixture.jdbcTemplate(), "event", "mid", new float[] { 2.0f, 0.15f });

        Fixture normalizedFixture = fixture();
        insertCanonical(normalizedFixture.jdbcTemplate(), "event", "far_aligned",
                new EmbeddingVector("nomic", new float[] { 5.0f, 0.0f }).normalized().values());
        insertCanonical(normalizedFixture.jdbcTemplate(), "event", "near_offaxis",
                new EmbeddingVector("nomic", new float[] { 0.9f, 0.6f }).normalized().values());
        insertCanonical(normalizedFixture.jdbcTemplate(), "event", "mid",
                new EmbeddingVector("nomic", new float[] { 2.0f, 0.15f }).normalized().values());

        List<String> rawRanking = rawFixture.vectorStore().knn(query(1.0f, 0.0f), 3, key -> true).stream()
                .map(ScoredKey::key)
                .toList();
        List<String> normalizedRanking = normalizedFixture.vectorStore().knn(query(1.0f, 0.0f), 3, key -> true)
                .stream()
                .map(ScoredKey::key)
                .toList();

        assertThat(rawRanking).containsExactly("event:far_aligned", "event:mid", "event:near_offaxis");
        assertThat(normalizedRanking).containsExactlyElementsOf(rawRanking);
    }

    @Test
    void returnsWholeCorpusWhenKIsLargerThanCorpus() {
        Fixture fixture = fixture();
        fixture.embeddingStore().upsert(stored("event", "one", new float[] { 1.0f, 0.0f }));
        fixture.embeddingStore().upsert(stored("event", "two", new float[] { 0.0f, 1.0f }));

        assertThat(fixture.vectorStore().knn(query(1.0f, 0.0f), 10, key -> true))
                .extracting(ScoredKey::key)
                .containsExactly("event:one", "event:two");
    }

    @Test
    void emptyCorpusReturnsEmptyResults() {
        Fixture fixture = fixture();

        assertThat(fixture.vectorStore().knn(query(1.0f, 0.0f), 3, key -> true)).isEmpty();
    }

    @Test
    void filterExcludesEverythingBeforeScoringResultsAreReturned() {
        Fixture fixture = fixture();
        fixture.embeddingStore().upsert(stored("event", "one", new float[] { 1.0f, 0.0f }));

        assertThat(fixture.vectorStore().knn(query(1.0f, 0.0f), 3, key -> false)).isEmpty();
    }

    @Test
    void fetchVectorsReturnsOnlyRequestedCanonicalVectorsForModelAndDimensions() {
        Fixture fixture = fixture();
        fixture.embeddingStore().upsert(stored("event", "one", new float[] { 1.0f, 0.0f }));
        fixture.embeddingStore().upsert(stored("event", "two", new float[] { 0.0f, 1.0f }));

        Map<String, EmbeddingVector> vectors = fixture.vectorStore().fetchVectors(
                List.of("event:one", "event:missing"),
                "nomic",
                2);

        assertThat(vectors).containsOnlyKeys("event:one");
        assertThat(vectors.get("event:one").cosineSimilarity(query(1.0f, 0.0f))).isEqualTo(1.0);
    }

    @Test
    void tiesBreakDeterministicallyByKey() {
        Fixture fixture = fixture();
        fixture.embeddingStore().upsert(stored("event", "c", new float[] { 1.0f, 0.0f }));
        fixture.embeddingStore().upsert(stored("event", "a", new float[] { 1.0f, 0.0f }));
        fixture.embeddingStore().upsert(stored("event", "b", new float[] { 1.0f, 0.0f }));

        assertThat(fixture.vectorStore().knn(query(1.0f, 0.0f), 2, key -> true))
                .extracting(ScoredKey::key)
                .containsExactly("event:a", "event:b");
    }

    private static Fixture fixture() {
        Path database = Path.of(
                System.getProperty("java.io.tmpdir"),
                "bb-brute-force-vector-store-test-" + UUID.randomUUID() + ".db");
        database.toFile().deleteOnExit();
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new Fixture(jdbcTemplate, new EmbeddingSqlStore(jdbcTemplate), new BruteForceVectorStore(jdbcTemplate));
    }

    private static void insertCanonical(JdbcTemplate jdbcTemplate, String targetKind, String targetId, float[] values) {
        EmbeddingVector vector = new EmbeddingVector("nomic", values);
        jdbcTemplate.update("""
                INSERT INTO memory_embeddings (
                    target_kind, target_id, model, dimensions, vector, content_hash, embedded_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                targetKind,
                targetId,
                vector.model(),
                vector.values().length,
                vector.toBlob(),
                "hash-" + targetId,
                Instant.parse("2026-07-27T12:00:00Z").toString());
    }

    private static StoredEmbedding stored(String targetKind, String targetId, float[] values) {
        return new StoredEmbedding(
                targetKind,
                targetId,
                new EmbeddingVector("nomic", values),
                "hash-" + targetId,
                Instant.parse("2026-07-27T12:00:00Z"));
    }

    private static EmbeddingVector query(float... values) {
        return new EmbeddingVector("nomic", values);
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            EmbeddingSqlStore embeddingStore,
            BruteForceVectorStore vectorStore) {
    }
}
