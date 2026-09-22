package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore.ScoredKey;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SqliteVecVectorStoreTest {

    @Test
    void sqliteVecInitializeLoadsExtensionWhenTableCreationNeedsIt() throws Exception {
        Path extensionPath = sqliteVecPath();
        Assumptions.assumeTrue(
                Files.isRegularFile(extensionPath), () -> "sqlite-vec extension not present at " + extensionPath);

        Fixture fixture = fixture(extensionPath, false);

        assertThat(fixture.sqliteVecStore().available()).isTrue();
    }

    @Test
    void sqliteVecAdapterMatchesBruteForceTopKOrdering() throws Exception {
        Path extensionPath = sqliteVecPath();
        Assumptions.assumeTrue(
                Files.isRegularFile(extensionPath), () -> "sqlite-vec extension not present at " + extensionPath);

        Fixture fixture = fixture(extensionPath);
        List<StoredEmbedding> corpus = List.of(
                stored("event", "far_aligned", new float[] {5.0f, 0.0f}),
                stored("event", "near_offaxis", new float[] {0.9f, 0.6f}),
                stored("event", "mid", new float[] {2.0f, 0.15f}));
        upsertAll(fixture, corpus);

        EmbeddingVector query = new EmbeddingVector("nomic", new float[] {1.0f, 0.0f});
        assertThat(fixture.sqliteVecStore().available()).isTrue();
        List<ScoredKey> actual = fixture.sqliteVecStore().knn(query, 3, key -> true);
        List<ScoredKey> expected = fixture.bruteForceStore().knn(query, 3, key -> true);

        assertThat(actual)
                .extracting(ScoredKey::key)
                .containsExactlyElementsOf(expected.stream().map(ScoredKey::key).toList());
        assertThat(actual)
                .extracting(ScoredKey::key)
                .containsExactly("event:far_aligned", "event:mid", "event:near_offaxis");
        assertThat(actual).hasSameSizeAs(expected);
        for (int index = 0; index < actual.size(); index++) {
            assertThat(actual.get(index).score()).isCloseTo(expected.get(index).score(), within(1.0e-4));
        }
        assertThat(fixture.sqliteVecStore().available()).isTrue();
    }

    @Test
    void sqliteVecOverfetchesBeforeApplyingSelectiveKeyFilter() throws Exception {
        Path extensionPath = sqliteVecPath();
        Assumptions.assumeTrue(
                Files.isRegularFile(extensionPath), () -> "sqlite-vec extension not present at " + extensionPath);

        Fixture fixture = fixture(extensionPath);
        List<StoredEmbedding> corpus = List.of(
                stored("event", "nearest-1", new float[] {1.0f, 0.0f}),
                stored("event", "nearest-2", new float[] {0.999f, 0.0447f}),
                stored("event", "nearest-3", new float[] {0.995f, 0.0998f}),
                stored("event", "nearest-4", new float[] {0.98f, 0.199f}),
                stored("event", "nearest-5", new float[] {0.96f, 0.28f}),
                stored("session_summary", "alpha", new float[] {0.8f, 0.6f}),
                stored("session_summary", "beta", new float[] {0.6f, 0.8f}),
                stored("session_summary", "gamma", new float[] {0.0f, 1.0f}));
        upsertAll(fixture, corpus);

        EmbeddingVector query = new EmbeddingVector("nomic", new float[] {1.0f, 0.0f});
        List<String> actual = fixture.sqliteVecStore().knn(query, 3, key -> key.startsWith("session_summary:")).stream()
                .map(ScoredKey::key)
                .toList();
        List<String> expected =
                fixture.bruteForceStore().knn(query, 3, key -> key.startsWith("session_summary:")).stream()
                        .map(ScoredKey::key)
                        .toList();

        assertThat(fixture.sqliteVecStore().available()).isTrue();
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(actual).containsExactly("session_summary:alpha", "session_summary:beta", "session_summary:gamma");
        assertThat(fixture.sqliteVecStore().available()).isTrue();
    }

    @Test
    void sqliteVecFallsBackWhenPathIsUnset() {
        Fixture fixture = fixtureWithoutExtension();
        fixture.embeddingStore().upsert(stored("event", "one", new float[] {1.0f, 0.0f}));

        assertThat(fixture.sqliteVecStore().available()).isFalse();
        assertThat(fixture.sqliteVecStore().knn(new EmbeddingVector("nomic", new float[] {1.0f, 0.0f}), 1, key -> true))
                .extracting(ScoredKey::key)
                .containsExactly("event:one");
        Map<String, EmbeddingVector> vectors =
                fixture.sqliteVecStore().fetchVectors(List.of("event:one", "event:missing"), "nomic", 2);
        assertThat(vectors).containsOnlyKeys("event:one");
        assertThat(vectors.get("event:one").cosineSimilarity(new EmbeddingVector("nomic", new float[] {1.0f, 0.0f})))
                .isEqualTo(1.0);
    }

    private static Fixture fixture(Path extensionPath) throws Exception {

        return fixture(extensionPath, true);
    }

    private static Fixture fixture(Path extensionPath, boolean preloadExtension) throws Exception {
        Path database = tempDatabase();
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("enable_load_extension", "true");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database, connectionProperties);
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        if (preloadExtension) {
            SqliteVecSupport.load(dataSource, extensionPath);
        }
        BruteForceVectorStore bruteForce = new BruteForceVectorStore(jdbcTemplate);
        SqliteVecVectorStore sqliteVec = new SqliteVecVectorStore(
                jdbcTemplate,
                dataSource,
                vectorProperties(extensionPath.toString()),
                embeddingProperties(),
                bruteForce);
        sqliteVec.initialize();

        return new Fixture(new EmbeddingSqlStore(jdbcTemplate, sqliteVecProvider(sqliteVec)), bruteForce, sqliteVec);
    }

    private static Fixture fixtureWithoutExtension() {
        Path database = tempDatabase();
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        BruteForceVectorStore bruteForce = new BruteForceVectorStore(jdbcTemplate);
        SqliteVecVectorStore sqliteVec = new SqliteVecVectorStore(
                jdbcTemplate, dataSource, vectorProperties(""), embeddingProperties(), bruteForce);
        sqliteVec.initialize();

        return new Fixture(new EmbeddingSqlStore(jdbcTemplate, sqliteVecProvider(sqliteVec)), bruteForce, sqliteVec);
    }

    private static Path tempDatabase() {
        Path database = Path.of(
                System.getProperty("java.io.tmpdir"), "bb-sqlite-vec-vector-store-test-" + UUID.randomUUID() + ".db");
        database.toFile().deleteOnExit();

        return database;
    }

    private static void upsertAll(Fixture fixture, List<StoredEmbedding> corpus) {
        corpus.forEach(embedding -> fixture.embeddingStore().upsert(embedding));
    }

    private static ObjectProvider<SqliteVecVectorStore> sqliteVecProvider(SqliteVecVectorStore sqliteVec) {

        return new ObjectProvider<>() {
            @Override
            public SqliteVecVectorStore getIfAvailable() {

                return sqliteVec;
            }

            @Override
            public void ifAvailable(Consumer<SqliteVecVectorStore> dependencyConsumer) {
                dependencyConsumer.accept(sqliteVec);
            }
        };
    }

    private static Path sqliteVecPath() {
        String configured = System.getenv("SBA_SQLITE_VEC_PATH");
        if (configured == null || configured.isBlank()) {
            configured = "/opt/homebrew/lib/node_modules/openclaw/node_modules/sqlite-vec-darwin-arm64/vec0.dylib";
        }

        return Path.of(configured);
    }

    private static MemoryVectorProperties vectorProperties(String sqliteVecPath) {
        MemoryVectorProperties properties = new MemoryVectorProperties();
        properties.setSqliteVecPath(sqliteVecPath);

        return properties;
    }

    private static MemoryEmbeddingProperties embeddingProperties() {
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        properties.setModel("nomic");
        properties.setDimensions(2);

        return properties;
    }

    private static StoredEmbedding stored(String targetKind, String targetId, float[] values) {

        return new StoredEmbedding(
                targetKind,
                targetId,
                new EmbeddingVector("nomic", values),
                "hash-" + targetId,
                Instant.parse("2026-07-27T12:00:00Z"));
    }

    private record Fixture(
            EmbeddingSqlStore embeddingStore,
            BruteForceVectorStore bruteForceStore,
            SqliteVecVectorStore sqliteVecStore) {}
}
