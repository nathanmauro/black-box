package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingSqlStoreTest {

    @Test
    void upsertNormalizesThenReadsThroughRealSqliteFile() {
        EmbeddingSqlStore store = store(tempDatabase());
        StoredEmbedding embedding = stored("event", "event-1", "nomic", new float[] { 3.0f, 4.0f }, "hash-1");

        store.upsert(embedding);

        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findHash("event", "event-1")).contains("hash-1");
        assertThat(store.hasCurrentEmbedding("event", "event-1", "hash-1", "nomic", 2)).isTrue();
        assertThat(store.hasCurrentEmbedding("event", "event-1", "hash-1", "other", 2)).isFalse();
        assertThat(store.hasCurrentEmbedding("event", "event-1", "hash-1", "nomic", 3)).isFalse();
        assertThat(store.loadAll("nomic", 2)).singleElement()
                .satisfies(stored -> {
                    assertThat(stored.targetKind()).isEqualTo("event");
                    assertThat(stored.targetId()).isEqualTo("event-1");
                    assertThat(stored.vector().model()).isEqualTo("nomic");
                    assertThat(stored.vector().values()).containsExactly(0.6f, 0.8f);
                    assertThat(stored.contentHash()).isEqualTo("hash-1");
                    assertThat(stored.embeddedAt()).isEqualTo(Instant.parse("2026-07-27T12:00:00Z"));
                });
    }

    @Test
    void upsertReplacesExistingTargetOnConflict() {
        EmbeddingSqlStore store = store(tempDatabase());
        store.upsert(stored("event", "event-1", "old-model", new float[] { 1.0f }, "old-hash"));

        store.upsert(stored("event", "event-1", "nomic", new float[] { 0.0f, 1.0f }, "new-hash"));

        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findHash("event", "event-1")).contains("new-hash");
        assertThat(store.loadAll("old-model", 1)).isEmpty();
        assertThat(store.loadAll("nomic", 2)).singleElement()
                .satisfies(stored -> assertThat(stored.vector().values()).containsExactly(0.0f, 1.0f));
    }

    @Test
    void loadAllFiltersByModelAndDimensions() {
        EmbeddingSqlStore store = store(tempDatabase());
        store.upsert(stored("event", "event-1", "nomic", new float[] { 1.0f, 0.0f }, "hash-1"));
        store.upsert(stored("event", "event-2", "nomic", new float[] { 1.0f }, "hash-2"));
        store.upsert(stored("session_summary", "session-1", "other", new float[] { 0.0f, 1.0f }, "hash-3"));

        assertThat(store.loadAll("nomic", 2))
                .extracting(StoredEmbedding::targetId)
                .containsExactly("event-1");
    }

    @Test
    void deleteForRemovesOnlyOneTarget() {
        EmbeddingSqlStore store = store(tempDatabase());
        store.upsert(stored("event", "event-1", "nomic", new float[] { 1.0f }, "hash-1"));
        store.upsert(stored("event", "event-2", "nomic", new float[] { 2.0f }, "hash-2"));

        store.deleteFor("event", "event-1");

        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findHash("event", "event-1")).isEmpty();
        assertThat(store.findHash("event", "event-2")).contains("hash-2");
    }

    private static EmbeddingSqlStore store(Path database) {
        database.toFile().deleteOnExit();
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        return new EmbeddingSqlStore(new JdbcTemplate(dataSource));
    }

    private static Path tempDatabase() {
        return Path.of(
                System.getProperty("java.io.tmpdir"),
                "bb-embedding-sql-store-test-" + UUID.randomUUID() + ".db");
    }

    private static StoredEmbedding stored(
            String targetKind, String targetId, String model, float[] values, String contentHash) {
        return new StoredEmbedding(
                targetKind,
                targetId,
                new EmbeddingVector(model, values),
                contentHash,
                Instant.parse("2026-07-27T12:00:00Z"));
    }
}
