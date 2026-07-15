package dev.nathan.sbaagentic.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectAliasRepository {

    private static final String NORMALIZED_SCOPE_SQL = """
            CASE
              WHEN cwd IS NULL OR trim(cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(cwd), '/')
            END
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProjectAliasRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProjectAlias> findAll() {
        return jdbcTemplate.query("""
                SELECT id, alias_key, canonical_key, source, created_at
                  FROM project_aliases
                 ORDER BY created_at ASC, alias_key ASC
                """, this::mapAlias);
    }

    public Optional<ProjectAlias> findByAliasKey(String aliasKey) {
        return jdbcTemplate.query("""
                SELECT id, alias_key, canonical_key, source, created_at
                  FROM project_aliases
                 WHERE alias_key = ?
                """, this::mapAlias, aliasKey).stream().findFirst();
    }

    public ProjectAlias insert(
            String id,
            String aliasKey,
            String canonicalKey,
            String source,
            Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO project_aliases (id, alias_key, canonical_key, source, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, aliasKey, canonicalKey, source, createdAt.toString());
        return new ProjectAlias(id, aliasKey, canonicalKey, source, createdAt);
    }

    public int delete(String aliasKey) {
        return jdbcTemplate.update("DELETE FROM project_aliases WHERE alias_key = ?", aliasKey);
    }

    public List<String> distinctObservedScopes() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT %s AS scope
                  FROM agent_sessions
                 ORDER BY scope ASC
                """.formatted(NORMALIZED_SCOPE_SQL), String.class);
    }

    private ProjectAlias mapAlias(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectAlias(
                rs.getString("id"),
                rs.getString("alias_key"),
                rs.getString("canonical_key"),
                rs.getString("source"),
                Instant.parse(rs.getString("created_at")));
    }
}
