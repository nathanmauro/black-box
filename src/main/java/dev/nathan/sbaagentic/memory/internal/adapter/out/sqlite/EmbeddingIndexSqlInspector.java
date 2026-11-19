package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingIndexInspector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Count-only queries; nothing here reads a vector, a text body, or a summary. */
@Repository
public class EmbeddingIndexSqlInspector implements EmbeddingIndexInspector {

    // Must match EmbeddingSourceSqlReader's notion of an embeddable event type.
    private static final String EMBEDDABLE_EVENT = """
            lower(replace(replace(replace(e.event_type, '_', ''), '-', ''), ' ', ''))
                IN ('decision', 'handoff', 'observation', 'idea', 'evidence')
            """;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SqliteVecVectorStore> sqliteVec;

    public EmbeddingIndexSqlInspector(JdbcTemplate jdbc, ObjectProvider<SqliteVecVectorStore> sqliteVec) {
        this.jdbc = jdbc;
        this.sqliteVec = sqliteVec;
    }

    @Override
    public Counts count(String model, int dimensions) {
        long embeddableEvents = count("SELECT count(*) FROM agent_events e WHERE " + EMBEDDABLE_EVENT);
        long embeddedEvents = count(
                """
                SELECT count(*) FROM memory_embeddings m
                  JOIN agent_events e ON e.id = m.target_id
                 WHERE m.target_kind = 'event' AND m.model = ? AND m.dimensions = ? AND
                """
                        + EMBEDDABLE_EVENT,
                model,
                dimensions);
        long orphaned = count("""
                SELECT count(*) FROM memory_embeddings m
                 WHERE (m.target_kind = 'event'
                        AND NOT EXISTS (SELECT 1 FROM agent_events e WHERE e.id = m.target_id))
                    OR (m.target_kind = 'session_summary'
                        AND NOT EXISTS (SELECT 1 FROM agent_sessions s WHERE s.id = m.target_id))
                """);
        long otherModel = count(
                "SELECT count(*) FROM memory_embeddings WHERE model <> ? OR dimensions <> ?", model, dimensions);
        long summaries = count("SELECT count(*) FROM agent_sessions WHERE trim(coalesce(summary, '')) <> ''");
        long embeddedSummaries = count(
                """
                SELECT count(*) FROM memory_embeddings
                 WHERE target_kind = 'session_summary' AND model = ? AND dimensions = ?
                """,
                model,
                dimensions);
        long currentModel = count(
                "SELECT count(*) FROM memory_embeddings WHERE model = ? AND dimensions = ?", model, dimensions);

        SqliteVecVectorStore vec = sqliteVec.getIfAvailable();
        boolean nativeIndex = vec != null && vec.available();
        Long vectorRows = nativeIndex ? jdbc.queryForObject("SELECT count(*) FROM memory_vec", Long.class) : null;

        return new Counts(
                embeddableEvents,
                embeddedEvents,
                orphaned,
                otherModel,
                summaries,
                embeddedSummaries,
                currentModel,
                nativeIndex ? "sqlite-vec" : "brute-force",
                vectorRows);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);

        return value == null ? 0 : value;
    }
}
