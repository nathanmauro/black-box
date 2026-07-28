package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Predicate;

import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BruteForceVectorStore implements MemoryVectorStore {

    private static final Comparator<ScoredKey> BEST_FIRST = Comparator
            .comparingDouble(ScoredKey::score)
            .reversed()
            .thenComparing(ScoredKey::key);

    private static final Comparator<ScoredKey> WORST_FIRST = Comparator
            .comparingDouble(ScoredKey::score)
            .thenComparing(ScoredKey::key, Comparator.reverseOrder());

    private final JdbcTemplate jdbcTemplate;

    public BruteForceVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ScoredKey> knn(EmbeddingVector query, int k, Predicate<String> keyFilter) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(keyFilter, "keyFilter");
        if (k <= 0) {
            return List.of();
        }
        PriorityQueue<ScoredKey> top = new PriorityQueue<>(Math.max(1, k), WORST_FIRST);
        for (StoredEmbedding embedding : loadAll(query.model(), query.values().length)) {
            String key = MemoryVectorKeys.key(embedding);
            if (!keyFilter.test(key)) {
                continue;
            }
            ScoredKey candidate = new ScoredKey(key, query.cosineSimilarity(embedding.vector()));
            if (top.size() < k) {
                top.add(candidate);
            }
            else if (BEST_FIRST.compare(candidate, top.peek()) < 0) {
                top.poll();
                top.add(candidate);
            }
        }
        return top.stream().sorted(BEST_FIRST).toList();
    }

    private List<StoredEmbedding> loadAll(String model, int dimensions) {
        return jdbcTemplate.query("""
                SELECT target_kind, target_id, model, vector, content_hash, embedded_at
                  FROM memory_embeddings
                 WHERE model = ?
                   AND dimensions = ?
                 ORDER BY target_kind, target_id
                """, this::mapEmbedding, model, dimensions);
    }

    private StoredEmbedding mapEmbedding(ResultSet rs, int rowNum) throws SQLException {
        EmbeddingVector vector = EmbeddingVector.fromBlob(
                rs.getString("model"),
                rs.getBytes("vector"));
        return new StoredEmbedding(
                rs.getString("target_kind"),
                rs.getString("target_id"),
                vector,
                rs.getString("content_hash"),
                Instant.parse(rs.getString("embedded_at")));
    }
}
