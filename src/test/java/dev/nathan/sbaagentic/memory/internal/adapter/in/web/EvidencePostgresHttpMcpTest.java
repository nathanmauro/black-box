package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
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
        URI target = URI.create(url.substring("jdbc:".length()));
        assertThat(target.getHost()).isIn("127.0.0.1", "localhost");
        assertThat(target.getPath()).isEqualTo("/blackbox_test");
        assertThat(target.getRawQuery()).isNull();
        assertThat(target.getUserInfo()).isNull();
        assertThat(target.getPort()).isEqualTo("true".equals(System.getenv("CI")) ? 5432 : 18877);

        return DriverManager.getConnection(
                url,
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test"),
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"));
    }

    @Override
    protected Map<String, Object> databaseProperties() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_database(), current_user, inet_server_port()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("blackbox_test");
                assertThat(result.getString(2)).isEqualTo("blackbox_test");
                assertThat(result.getInt(3)).isEqualTo("true".equals(System.getenv("CI")) ? 5432 : 18877);
            }
            String expectedDirectory = System.getenv("SBA_POSTGRES_TEST_EXPECTED_DATA_DIRECTORY");
            if (expectedDirectory != null)
                try (var result = statement.executeQuery("SHOW data_directory")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(expectedDirectory);
                }
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
