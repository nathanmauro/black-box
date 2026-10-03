package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Own random schemas only, after verifying the explicitly configured disposable loopback server. */
public final class BraidPostgresFixture implements AutoCloseable {
    private final String schema = "bb_braid_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;

    public Connection connection() throws Exception {
        String url = System.getenv("SBA_POSTGRES_TEST_URL");
        assertThat(url).startsWith("jdbc:postgresql://");
        URI target = URI.create(url.substring(5));
        assertThat(target.getHost()).isIn("127.0.0.1", "localhost");
        assertThat(target.getPath()).isEqualTo("/blackbox_test");
        assertThat(target.getPort()).isBetween(1, 65535);
        assertThat(target.getUserInfo()).isNull();
        assertThat(target.getRawQuery()).isNull();
        assertThat(target.getRawFragment()).isNull();
        String user = System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test");
        assertThat(user).isEqualTo("blackbox_test");
        Connection connection = DriverManager.getConnection(
                url, user, System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"));
        try {
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery(
                            "SELECT current_database(),current_user,inet_server_port(),current_setting('data_directory')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("blackbox_test");
                assertThat(result.getString(2)).isEqualTo(user);
                assertThat(result.getInt(3)).isEqualTo(target.getPort());
                String expected = System.getenv("SBA_POSTGRES_TEST_EXPECTED_DATA_DIRECTORY");
                if (expected != null) assertThat(result.getString(4)).isEqualTo(expected);
            }

            return connection;
        } catch (Exception | AssertionError error) {
            try {
                connection.close();
            } catch (SQLException close) {
                error.addSuppressed(close);
            }
            throw error;
        }
    }

    public void create() throws Exception {
        if (created)

            return;
        try (Connection connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            created = true;
        }
    }

    public Connection schemaConnection() throws Exception {
        create();
        Connection connection = connection();
        try {
            connection.setSchema(schema);

            return connection;
        } catch (Exception error) {
            connection.close();
            throw error;
        }
    }

    public List<String> applicationArguments() throws Exception {
        create();

        return List.of(
                "--spring.profiles.active=postgres",
                "--spring.datasource.url=" + System.getenv("SBA_POSTGRES_TEST_URL"),
                "--spring.datasource.username=blackbox_test",
                "--spring.datasource.password="
                        + System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"),
                "--spring.datasource.hikari.data-source-properties.currentSchema=" + schema);
    }

    @Override
    public void close() throws Exception {
        if (created) {
            try (Connection connection = connection();
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
            created = false;
        }
    }
}
