package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Primary
@Repository
@ConditionalOnProperty(name = "sba.storage.backend", havingValue = "sqlite", matchIfMissing = true)
public class SqliteVecVectorStore implements MemoryVectorStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteVecVectorStore.class);
    private static final int OVERFETCH_MULTIPLIER = 4;
    private static final int MIN_FILTERED_FETCH = 32;
    private static final Comparator<ScoredKey> BEST_FIRST =
            Comparator.comparingDouble(ScoredKey::score).reversed().thenComparing(ScoredKey::key);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final MemoryVectorProperties vectorProperties;
    private final MemoryEmbeddingProperties embeddingProperties;
    private final BruteForceVectorStore fallback;
    private final AtomicBoolean available = new AtomicBoolean();

    public SqliteVecVectorStore(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            MemoryVectorProperties vectorProperties,
            MemoryEmbeddingProperties embeddingProperties,
            BruteForceVectorStore fallback) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.vectorProperties = vectorProperties;
        this.embeddingProperties = embeddingProperties;
        this.fallback = fallback;
    }

    @PostConstruct
    public void initialize() {
        Optional<Path> extensionPath = SqliteVecSupport.configuredExistingPath(vectorProperties);
        if (extensionPath.isEmpty()) {
            available.set(false);

            return;
        }
        if (createTable(false)) {
            available.set(true);

            return;
        }
        if (SqliteVecSupport.load(dataSource, extensionPath.get()) && createTable(true)) {
            available.set(true);
        } else {
            available.set(false);
        }
    }

    private boolean createTable(boolean logFailure) {
        try {
            jdbcTemplate.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS memory_vec
                    USING vec0(key TEXT PRIMARY KEY, embedding float[%d])
                    """.formatted(embeddingProperties.getDimensions()));

            return true;
        } catch (DataAccessException ex) {
            if (logFailure) {
                SqliteVecSupport.logUnavailableOnce(
                        "sqlite-vec memory_vec table could not be created; " + "using brute-force memory vectors", ex);
            }

            return false;
        }
    }

    @Override
    public List<ScoredKey> knn(EmbeddingVector query, int k, Predicate<String> keyFilter) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(keyFilter, "keyFilter");
        if (k <= 0) {

            return List.of();
        }
        if (!available.get()) {

            return fallback.knn(query, k, keyFilter);
        }
        try {
            int vectorCount = vectorCount();
            if (vectorCount == 0) {

                return List.of();
            }
            int fetchLimit = initialFetchLimit(k, vectorCount);
            while (true) {
                List<ScoredKey> matches = queryNearest(query, fetchLimit);
                List<ScoredKey> filtered = matches.stream()
                        .sorted(BEST_FIRST)
                        .filter(scored -> keyFilter.test(scored.key()))
                        .limit(k)
                        .toList();
                if (filtered.size() == k || matches.size() < fetchLimit || fetchLimit >= vectorCount) {

                    return filtered;
                }
                fetchLimit = growFetchLimit(fetchLimit, vectorCount);
            }
        } catch (DataAccessException ex) {
            available.set(false);
            LOGGER.info("sqlite-vec query failed; using brute-force memory vectors", ex);

            return fallback.knn(query, k, keyFilter);
        }
    }

    @Override
    public Map<String, EmbeddingVector> fetchVectors(Collection<String> keys, String model, int dimensions) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(model, "model");
        List<String> distinctKeys =
                keys.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctKeys.isEmpty()) {

            return Map.of();
        }

        String placeholders = String.join(", ", java.util.Collections.nCopies(distinctKeys.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(model);
        args.add(dimensions);
        args.addAll(distinctKeys);
        List<Map.Entry<String, EmbeddingVector>> rows = jdbcTemplate.query(
                """
                SELECT target_kind, target_id, model, vector
                  FROM memory_embeddings
                 WHERE model = ?
                   AND dimensions = ?
                   AND (target_kind || ':' || target_id) IN (%s)
                 ORDER BY target_kind, target_id
                """.formatted(placeholders),
                (rs, rowNum) -> Map.entry(
                        MemoryVectorKeys.key(rs.getString("target_kind"), rs.getString("target_id")),
                        EmbeddingVector.fromBlob(rs.getString("model"), rs.getBytes("vector"))),
                args.toArray());
        Map<String, EmbeddingVector> vectors = new LinkedHashMap<>();
        rows.forEach(row -> vectors.put(row.getKey(), row.getValue()));

        return vectors;
    }

    private int vectorCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_vec", Long.class);
        if (count == null || count <= 0) {

            return 0;
        }
        if (count > Integer.MAX_VALUE) {

            return Integer.MAX_VALUE;
        }

        return count.intValue();
    }

    private List<ScoredKey> queryNearest(EmbeddingVector query, int fetchLimit) {

        return jdbcTemplate.query(
                """
                SELECT key, distance
                  FROM memory_vec
                 WHERE embedding MATCH ?
                   AND k = ?
                 ORDER BY distance
                """,
                (rs, rowNum) -> new ScoredKey(rs.getString("key"), cosineFromL2Distance(rs.getDouble("distance"))),
                query.normalized().toBlob(),
                fetchLimit);
    }

    private static double cosineFromL2Distance(double distance) {
        double cosine = 1.0 - (distance * distance) / 2.0;

        return Math.max(-1.0, Math.min(1.0, cosine));
    }

    private static int initialFetchLimit(int k, int vectorCount) {
        long requested = Math.max((long) k, (long) k * OVERFETCH_MULTIPLIER);
        requested = Math.max(requested, MIN_FILTERED_FETCH);

        return (int) Math.min(vectorCount, requested);
    }

    private static int growFetchLimit(int fetchLimit, int vectorCount) {
        long next = Math.max((long) fetchLimit + 1L, (long) fetchLimit * 2L);

        return (int) Math.min(vectorCount, next);
    }

    void upsert(StoredEmbedding embedding) {
        if (!available.get()) {

            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    INSERT OR REPLACE INTO memory_vec(key, embedding)
                    VALUES (?, ?)
                    """, MemoryVectorKeys.key(embedding), embedding.vector().toBlob());
        } catch (DataAccessException ex) {
            available.set(false);
            LOGGER.info("sqlite-vec upsert failed; using brute-force memory vectors", ex);
        }
    }

    void deleteFor(String targetKind, String targetId) {
        if (!available.get()) {

            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM memory_vec WHERE key = ?", MemoryVectorKeys.key(targetKind, targetId));
        } catch (DataAccessException ex) {
            available.set(false);
            LOGGER.info("sqlite-vec delete failed; using brute-force memory vectors", ex);
        }
    }

    boolean available() {

        return available.get();
    }
}
