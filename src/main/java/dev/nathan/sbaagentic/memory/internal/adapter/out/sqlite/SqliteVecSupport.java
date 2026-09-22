package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SqliteVecSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteVecSupport.class);
    private static final AtomicBoolean LOGGED_UNAVAILABLE = new AtomicBoolean();

    private SqliteVecSupport() {}

    static Optional<Path> configuredExistingPath(MemoryVectorProperties properties) {
        String configured = properties.getSqliteVecPath();
        if (configured == null || configured.isBlank()) {
            logUnavailableOnce("sqlite-vec path is not configured; using brute-force memory vectors", null);

            return Optional.empty();
        }
        Path path = Path.of(configured);
        if (!Files.isRegularFile(path)) {
            logUnavailableOnce(
                    "sqlite-vec extension file does not exist at " + path + "; using brute-force memory vectors", null);

            return Optional.empty();
        }

        return Optional.of(path);
    }

    static boolean canLoad(String jdbcUrl, Properties dataSourceProperties, Path extensionPath) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            logUnavailableOnce(
                    "sqlite-vec extension was configured but no JDBC URL is available; "
                            + "using brute-force memory vectors",
                    null);

            return false;
        }
        Properties connectionProperties = new Properties();
        connectionProperties.putAll(dataSourceProperties);
        connectionProperties.setProperty("enable_load_extension", "true");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, connectionProperties)) {
            load(connection, extensionPath);

            return true;
        } catch (SQLException ex) {
            logUnavailableOnce(
                    "sqlite-vec extension failed to load from " + extensionPath + "; using brute-force memory vectors",
                    ex);

            return false;
        }
    }

    static boolean load(DataSource dataSource, Path extensionPath) {
        try (Connection connection = dataSource.getConnection()) {
            load(connection, extensionPath);

            return true;
        } catch (SQLException ex) {
            logUnavailableOnce(
                    "sqlite-vec extension failed to load from " + extensionPath + "; using brute-force memory vectors",
                    ex);

            return false;
        }
    }

    static void load(Connection connection, Path extensionPath) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT load_extension(" + sqlLiteral(extensionPath.toString()) + ")");
        }
    }

    static String loadExtensionSql(Path extensionPath) {

        return "SELECT load_extension(" + sqlLiteral(extensionPath.toString()) + ")";
    }

    static void logUnavailableOnce(String message, Throwable throwable) {
        if (LOGGED_UNAVAILABLE.compareAndSet(false, true)) {
            if (throwable == null) {
                LOGGER.info(message);
            } else {
                LOGGER.info(message, throwable);
            }
        }
    }

    private static String sqlLiteral(String value) {

        return "'" + value.replace("'", "''") + "'";
    }
}
