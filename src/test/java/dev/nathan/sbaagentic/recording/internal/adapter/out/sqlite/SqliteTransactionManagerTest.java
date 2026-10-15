package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SQLite's single writer lock against real pooled connections, as application.yml configures them. */
class SqliteTransactionManagerTest {
    @TempDir
    Path directory;

    @Test
    void readThenWriteTransactionStartsOnlyAfterAnotherWriterCommits() throws Exception {
        String url = database("waits.db");
        try (HikariDataSource pool = pool(url, 5_000, 4);
                Connection writer = DriverManager.getConnection(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            TransactionTemplate transactions = new TransactionTemplate(new SqliteTransactionManager(pool));
            execute(writer, "BEGIN IMMEDIATE", "INSERT INTO notes (body) VALUES ('concurrent writer')");
            CompletableFuture<Long> committed = CompletableFuture.supplyAsync(() -> {
                sleep(Duration.ofMillis(400));
                long releasing = System.nanoTime();
                execute(writer, "COMMIT");

                return releasing;
            });
            AtomicLong startedAt = new AtomicLong();

            transactions.executeWithoutResult(status -> {
                startedAt.set(System.nanoTime());
                Integer seen = jdbc.queryForObject("SELECT count(*) FROM notes", Integer.class);
                jdbc.update("INSERT INTO notes (body) VALUES (?)", "after " + seen);
            });

            assertThat(startedAt.get()).isGreaterThan(committed.get(10, TimeUnit.SECONDS));
            assertThat(jdbc.queryForList("SELECT body FROM notes ORDER BY id", String.class))
                    .containsExactly("seed", "concurrent writer", "after 2");
        }
    }

    @Test
    void writerOutlastingBusyTimeoutFailsTheBeginAndLeavesThePooledConnectionClean() throws Exception {
        String url = database("timeout.db");
        // One pooled connection, so every later transaction reuses the one whose begin timed out.
        try (HikariDataSource pool = pool(url, 200, 1);
                Connection writer = DriverManager.getConnection(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            TransactionTemplate transactions = new TransactionTemplate(new SqliteTransactionManager(pool));
            execute(writer, "BEGIN IMMEDIATE");

            assertThatThrownBy(() -> transactions.executeWithoutResult(
                            status -> jdbc.update("INSERT INTO notes (body) VALUES ('never written')")))
                    .isInstanceOf(CannotCreateTransactionException.class)
                    .rootCause()
                    .hasMessageContaining("SQLITE_BUSY");
            execute(writer, "COMMIT");

            try (Connection pooled = pool.getConnection()) {
                assertThat(pooled.getAutoCommit()).isTrue();
            }
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                        jdbc.update("INSERT INTO notes (body) VALUES ('rolled back')");
                        throw new IllegalStateException("fixture failure after a write");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO notes (body) VALUES ('committed')"));
            assertThat(jdbc.queryForList("SELECT body FROM notes ORDER BY id", String.class))
                    .containsExactly("seed", "committed");
        }
    }

    @Test
    void readOnlyTransactionStaysDeferredAndNeverBlocksAWriter() throws Exception {
        String url = database("read-only.db");
        Properties noWaiting = new Properties();
        noWaiting.setProperty("busy_timeout", "0");
        try (HikariDataSource pool = pool(url, 5_000, 4);
                Connection writer = DriverManager.getConnection(url, noWaiting)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            TransactionTemplate reads = new TransactionTemplate(new SqliteTransactionManager(pool));
            reads.setReadOnly(true);

            reads.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT count(*) FROM notes", Integer.class);
                execute(writer, "BEGIN IMMEDIATE", "INSERT INTO notes (body) VALUES ('while reading')", "COMMIT");
            });

            assertThat(jdbc.queryForList("SELECT body FROM notes ORDER BY id", String.class))
                    .containsExactly("seed", "while reading");
        }
    }

    @Test
    void onlyTheSqliteBackendReplacesBootsTransactionManager() {
        ApplicationContextRunner context = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionManagerCustomizationAutoConfiguration.class))
                .withUserConfiguration(SqliteTransactionConfiguration.class)
                .withBean(DataSource.class, () -> new DriverManagerDataSource(database("wiring.db")))
                .withPropertyValues("spring.transaction.default-timeout=7s");

        context.run(app -> assertThat(app.getBean(PlatformTransactionManager.class))
                .isInstanceOf(SqliteTransactionManager.class)
                .extracting(manager -> ((JdbcTransactionManager) manager).getDefaultTimeout())
                .isEqualTo(7));
        context.withPropertyValues("sba.storage.backend=postgres")
                .run(app -> assertThat(app.getBean(PlatformTransactionManager.class))
                        .isExactlyInstanceOf(JdbcTransactionManager.class));
    }

    private String database(String name) {
        String url = "jdbc:sqlite:" + directory.resolve(name);
        execute(url, "PRAGMA journal_mode=WAL");
        execute(url, "CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)");
        execute(url, "INSERT INTO notes (body) SELECT 'seed' WHERE NOT EXISTS (SELECT 1 FROM notes)");

        return url;
    }

    /** The pool shape application.yml gives Hikari outside the PostgreSQL profile. */
    private static HikariDataSource pool(String url, int busyTimeoutMillis, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(size);
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("busy_timeout", Integer.toString(busyTimeoutMillis));

        return new HikariDataSource(config);
    }

    private static void execute(String url, String sql) {
        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void execute(Connection connection, String... statements) {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
