package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingSqlStore implements EmbeddingStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<SqliteVecVectorStore> sqliteVecVectorStore;

    @Autowired
    public EmbeddingSqlStore(JdbcTemplate jdbcTemplate, ObjectProvider<SqliteVecVectorStore> sqliteVecVectorStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteVecVectorStore = sqliteVecVectorStore;
    }

    EmbeddingSqlStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteVecVectorStore = null;
    }

    @Override
    public void upsert(StoredEmbedding embedding) {
        StoredEmbedding normalized = normalize(embedding);
        jdbcTemplate.update(
                """
                INSERT INTO memory_embeddings (
                    target_kind, target_id, model, dimensions, vector, content_hash, embedded_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(target_kind, target_id) DO UPDATE SET
                    model = excluded.model,
                    dimensions = excluded.dimensions,
                    vector = excluded.vector,
                    content_hash = excluded.content_hash,
                    embedded_at = excluded.embedded_at
                """,
                normalized.targetKind(),
                normalized.targetId(),
                normalized.vector().model(),
                normalized.vector().values().length,
                normalized.vector().toBlob(),
                normalized.contentHash(),
                normalized.embeddedAt().toString());
        if (sqliteVecVectorStore != null) {
            sqliteVecVectorStore.ifAvailable(store -> store.upsert(normalized));
        }
    }

    @Override
    public Optional<String> findHash(String targetKind, String targetId) {
        try {

            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT content_hash
                      FROM memory_embeddings
                     WHERE target_kind = ?
                       AND target_id = ?
                    """, String.class, targetKind, targetId));
        } catch (EmptyResultDataAccessException ex) {

            return Optional.empty();
        }
    }

    @Override
    public boolean hasCurrentEmbedding(
            String targetKind, String targetId, String contentHash, String model, int dimensions) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM memory_embeddings
                 WHERE target_kind = ?
                   AND target_id = ?
                   AND content_hash = ?
                   AND model = ?
                   AND dimensions = ?
                """, Long.class, targetKind, targetId, contentHash, model, dimensions);

        return count != null && count > 0;
    }

    @Override
    public List<StoredEmbedding> loadAll(String model, int dimensions) {

        return jdbcTemplate.query("""
                SELECT target_kind, target_id, model, vector, content_hash, embedded_at
                  FROM memory_embeddings
                 WHERE model = ?
                   AND dimensions = ?
                 ORDER BY target_kind, target_id
                """, this::mapEmbedding, model, dimensions);
    }

    @Override
    public void deleteFor(String targetKind, String targetId) {
        jdbcTemplate.update("""
                DELETE FROM memory_embeddings
                 WHERE target_kind = ?
                   AND target_id = ?
                """, targetKind, targetId);
        if (sqliteVecVectorStore != null) {
            sqliteVecVectorStore.ifAvailable(store -> store.deleteFor(targetKind, targetId));
        }
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_embeddings", Long.class);

        return count == null ? 0L : count;
    }

    private StoredEmbedding mapEmbedding(ResultSet rs, int rowNum) throws SQLException {
        EmbeddingVector vector = EmbeddingVector.fromBlob(rs.getString("model"), rs.getBytes("vector"));

        return new StoredEmbedding(
                rs.getString("target_kind"),
                rs.getString("target_id"),
                vector,
                rs.getString("content_hash"),
                Instant.parse(rs.getString("embedded_at")));
    }

    private static StoredEmbedding normalize(StoredEmbedding embedding) {

        return new StoredEmbedding(
                embedding.targetKind(),
                embedding.targetId(),
                embedding.vector().normalized(),
                embedding.contentHash(),
                embedding.embeddedAt());
    }
}
