package dev.nathan.sbaagentic.project;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectAliasSchemaTest {

    @Test
    void schemaAddsAliasTableAndUniqueAliasKeyIdempotently(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("project-alias-schema.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");

        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(dataSource);
        schema.execute(dataSource);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sqlite_master
                 WHERE type = 'table' AND name = 'project_aliases'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("PRAGMA table_info(project_aliases)"))
                .extracting(row -> String.valueOf(row.get("name")))
                .contains("id", "alias_key", "canonical_key", "source", "created_at");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sqlite_master
                 WHERE type = 'index' AND name = 'idx_project_aliases_canonical'
                """, Integer.class)).isEqualTo(1);

        jdbc.update("""
                INSERT INTO project_aliases (id, alias_key, canonical_key, source, created_at)
                VALUES ('one', '/tmp/alias', '/tmp/first', 'manual', '2026-07-15T12:00:00Z')
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_aliases (id, alias_key, canonical_key, source, created_at)
                VALUES ('two', '/tmp/alias', '/tmp/second', 'manual', '2026-07-15T12:01:00Z')
                """))
                .hasMessageContaining("project_aliases.alias_key");
    }
}
