package dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

class RetiredWorkflowSchemaMigrationTest {
    @TempDir
    Path directory;

    @Test
    void ordinaryStartupLeavesLegacyDataUntouchedAndExplicitFlagRetiresIt() {
        var dataSource = dataSource("flag.db");
        var jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("contracts/pre-task-retirement.sqlite.sql"))
                .execute(dataSource);
        jdbc.update(
                "INSERT INTO session_links VALUES ('linked','parent','child','spawned','task','2026-07-17T12:00:00Z')");
        var context = new ApplicationContextRunner()
                .withUserConfiguration(RetiredWorkflowSchemaMigration.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.run(app -> {
            assertThat(app).doesNotHaveBean(RetiredWorkflowSchemaMigration.class);
            assertThat(jdbc.queryForObject("SELECT task_id FROM session_links", String.class))
                    .isEqualTo("task");
            assertThat(retiredTables(jdbc)).hasSize(3);
        });
        context.withPropertyValues("sba.storage.retire-workflow=true").run(app -> {
            assertThat(app).hasSingleBean(RetiredWorkflowSchemaMigration.class);
            assertThat(retiredTables(jdbc)).isEmpty();
            assertThat(jdbc.queryForList("PRAGMA table_info(session_links)"))
                    .noneMatch(c -> "task_id".equals(c.get("name")));
            assertThat(jdbc.queryForObject("SELECT id FROM session_links", String.class))
                    .isEqualTo("linked");
        });
    }

    @Test
    void unexpectedColumnDependencyRollsBackNullingAndKeepsBoardTables() {
        var dataSource = dataSource("rollback.db");
        var jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("contracts/pre-task-retirement.sqlite.sql"))
                .execute(dataSource);
        jdbc.update("INSERT INTO session_links VALUES ('link','p','c','spawned','task','now')");
        jdbc.execute("CREATE INDEX unexpected_task_reference ON session_links(task_id)");
        var migration =
                new RetiredWorkflowSchemaMigration(jdbc, new DataSourceTransactionManager(dataSource), "sqlite");
        assertThatThrownBy(migration::migrate).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT task_id FROM session_links", String.class))
                .isEqualTo("task");
        assertThat(retiredTables(jdbc)).hasSize(3);
    }

    private java.util.List<String> retiredTables(JdbcTemplate jdbc) {

        return jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('specs','tasks','task_events')",
                String.class);
    }

    private DriverManagerDataSource dataSource(String filename) {
        var properties = new java.util.Properties();
        properties.setProperty("foreign_keys", "true");
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve(filename), properties);
        dataSource.setDriverClassName("org.sqlite.JDBC");

        return dataSource;
    }
}
