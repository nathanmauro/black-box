package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Identical consumer checks on a confirmed disposable PostgreSQL schema, never a live namespace. */
@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class EvidencePostgresHttpMcpTest extends EvidenceHttpMcpTest {
    private final String schema = "bb_evidence_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;

    private Connection connection() throws Exception {
        String url = System.getenv("SBA_POSTGRES_TEST_URL");
        String username = System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test");
        URI target = checkedTarget(url, username);
        Connection connection = DriverManager.getConnection(
                url, username, System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"));
        try {
            verifyIdentity(connection, target.getPort(), System.getenv("SBA_POSTGRES_TEST_EXPECTED_DATA_DIRECTORY"));

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

    static URI checkedTarget(String url, String username) {
        assertThat(url).as("disposable fixture JDBC URL").startsWith("jdbc:postgresql://");
        URI target = URI.create(url.substring("jdbc:".length()));
        assertThat(target.getHost()).as("fixture loopback host").isIn("127.0.0.1", "localhost");
        assertThat(target.getPath()).as("fixture database").isEqualTo("/blackbox_test");
        assertThat(username).as("fixture username").isEqualTo("blackbox_test");
        assertThat(target.getRawQuery()).as("fixture URL query").isNull();
        assertThat(target.getRawFragment()).as("fixture URL fragment").isNull();
        assertThat(target.getUserInfo()).as("fixture URL user information").isNull();
        assertThat(target.getPort()).as("explicit fixture port").isBetween(1, 65535);

        return target;
    }

    static void verifyIdentity(Connection connection, int port, String expectedDirectory) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_database(), current_user, inet_server_port()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).as("observed fixture database").isEqualTo("blackbox_test");
                assertThat(result.getString(2)).as("observed fixture user").isEqualTo("blackbox_test");
                assertThat(result.getInt(3)).as("observed fixture server port").isEqualTo(port);
            }
            if (expectedDirectory != null)
                try (var result = statement.executeQuery("SHOW data_directory")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1))
                            .as("observed fixture data directory")
                            .isEqualTo(expectedDirectory);
                }
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
