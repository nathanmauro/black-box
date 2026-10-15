package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The same continuation checks on a confirmed disposable PostgreSQL schema, never a live namespace. */
@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class ContinuationPostgresApiTest extends ContinuationApiTest {
    private final String schema =
            "bb_continuation_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;

    private Connection connection() throws Exception {
        String url = System.getenv("SBA_POSTGRES_TEST_URL");
        String username = System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test");
        URI target = EvidencePostgresHttpMcpTest.checkedTarget(url, username);
        Connection connection = DriverManager.getConnection(
                url, username, System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"));
        try {
            EvidencePostgresHttpMcpTest.verifyIdentity(
                    connection, target.getPort(), System.getenv("SBA_POSTGRES_TEST_EXPECTED_DATA_DIRECTORY"));

            return connection;
        } catch (Exception | AssertionError failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    protected Map<String, Object> databaseProperties() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            created = true;
        }

        return Map.of(
                "spring.datasource.url",
                System.getenv("SBA_POSTGRES_TEST_URL"),
                "spring.datasource.username",
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test"),
                "spring.datasource.password",
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"),
                "spring.datasource.hikari.data-source-properties.currentSchema",
                schema);
    }

    @Override
    protected String[] databaseProfiles() {

        return new String[] {"postgres"};
    }

    @Override
    protected void cleanupDatabase() throws Exception {
        if (created)
            try (var connection = connection();
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
    }
}
