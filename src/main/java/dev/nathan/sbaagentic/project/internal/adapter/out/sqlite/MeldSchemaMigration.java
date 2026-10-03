package dev.nathan.sbaagentic.project.internal.adapter.out.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.query.SqlInstant;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional upgrade of existing meld ownership; never rewrites capture or input rows. */
@Component
@DependsOnDatabaseInitialization
public class MeldSchemaMigration {
    private static final List<String> OLD_COLUMNS = List.of(
            "id",
            "project_key",
            "title",
            "body",
            "provider",
            "model",
            "prompt_version",
            "execution_mode",
            "saved_from_preview",
            "metadata_json",
            "created_at");
    private static final String OWNERSHIP = "CONSTRAINT ck_session_melds_ownership CHECK "
            + "(artifact_kind IN ('meld', 'braid') AND (project_key IS NOT NULL OR artifact_kind = 'braid'))";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final boolean postgres;

    public MeldSchemaMigration(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            @Value("${sba.storage.backend:sqlite}") String backend) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.postgres = "postgres".equals(backend);
    }

    @PostConstruct
    public void migrate() {
        transactions.executeWithoutResult(status -> {
            if (postgres) migratePostgres();
            else migrateSqlite();
            String key = SqlInstant.column("created_at", postgres).indexColumns();
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_session_melds_unassigned_chronology_v1 ON session_melds ("
                    + key + ") WHERE artifact_kind = 'braid' AND project_key IS NULL");
        });
    }

    private void migratePostgres() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='session_melds'",
                String.class);
        if (columns.contains("artifact_kind")) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_constraint "
                            + "WHERE conrelid='session_melds'::regclass AND conname='ck_session_melds_ownership' AND contype='c')",
                    Boolean.class))) throw unsupported();

            return;
        }
        jdbc.execute("ALTER TABLE session_melds ADD COLUMN artifact_kind TEXT NOT NULL DEFAULT 'meld'");
        backfillKinds("session_melds");
        jdbc.execute("ALTER TABLE session_melds ADD " + OWNERSHIP);
        jdbc.execute("ALTER TABLE session_melds ALTER COLUMN project_key DROP NOT NULL");
    }

    private void migrateSqlite() {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(session_melds)");
        String sql = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='session_melds'", String.class);
        if (columns.stream().anyMatch(c -> "artifact_kind".equals(c.get("name")))) {
            if (sql == null || !sql.contains("ck_session_melds_ownership")) throw unsupported();

            return;
        }
        verifyLegacySqlite(columns, sql);
        List<String> attached = jdbc.queryForList(
                "SELECT sql FROM sqlite_master WHERE tbl_name='session_melds' "
                        + "AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name",
                String.class);
        jdbc.execute(
                "CREATE TABLE session_melds_braid_upgrade (id TEXT PRIMARY KEY, project_key TEXT, title TEXT NOT NULL, "
                        + "body TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, prompt_version TEXT NOT NULL, "
                        + "execution_mode TEXT NOT NULL, saved_from_preview INTEGER NOT NULL, metadata_json TEXT, created_at TEXT NOT NULL, "
                        + "artifact_kind TEXT NOT NULL DEFAULT 'meld', " + OWNERSHIP + ")");
        String names = String.join(",", OLD_COLUMNS);
        jdbc.execute("INSERT INTO session_melds_braid_upgrade (" + names + ") SELECT " + names + " FROM session_melds");
        backfillKinds("session_melds_braid_upgrade");
        jdbc.execute("DROP TABLE session_melds");
        jdbc.execute("ALTER TABLE session_melds_braid_upgrade RENAME TO session_melds");
        for (String definition : attached) jdbc.execute(definition);
    }

    private void verifyLegacySqlite(List<Map<String, Object>> columns, String sql) {
        if (!columns.stream().map(c -> String.valueOf(c.get("name"))).toList().equals(OLD_COLUMNS)) throw unsupported();
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("name"));
            String type = name.equals("saved_from_preview") ? "INTEGER" : "TEXT";
            int nullable = Set.of("id", "metadata_json").contains(name) ? 0 : 1;
            if (!type.equalsIgnoreCase(String.valueOf(column.get("type")))
                    || column.get("dflt_value") != null
                    || ((Number) column.get("notnull")).intValue() != nullable
                    || ((Number) column.get("pk")).intValue() != (name.equals("id") ? 1 : 0)) throw unsupported();
        }
        // No general DDL rewriting: reject custom table invariants before replacing its known shape.
        String upper = sql == null ? "" : sql.toUpperCase(Locale.ROOT);
        if (upper.matches(
                "(?s).*\\b(CHECK|REFERENCES|UNIQUE|CONSTRAINT|COLLATE|DEFAULT|GENERATED|STRICT|WITHOUT|CONFLICT)\\b.*"))
            throw unsupported();
        if (!jdbc.queryForList("PRAGMA foreign_key_list(session_melds)").isEmpty()) throw unsupported();
        if (jdbc.queryForList("PRAGMA index_list(session_melds)").stream().anyMatch(i -> "u".equals(i.get("origin"))))
            throw unsupported();
        for (String table : jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class)) {
            String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
            if (jdbc.queryForList("PRAGMA foreign_key_list(" + quoted + ")").stream()
                    .anyMatch(fk -> "session_melds".equalsIgnoreCase(String.valueOf(fk.get("table")))))
                throw unsupported();
        }
    }

    private void backfillKinds(String table) {
        // Table is an internal constant. Parse conservatively; historically metadata was arbitrary TEXT.
        List<String> braids = new ArrayList<>();
        jdbc.query("SELECT id,metadata_json FROM " + table, row -> {
            try {
                JsonNode metadata = mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(row.getString("metadata_json"));
                if (metadata != null
                        && metadata.isObject()
                        && metadata.path("kind").isTextual()
                        && metadata.path("kind").asText().equals("braid")) braids.add(row.getString("id"));
            } catch (JsonProcessingException | IllegalArgumentException ignored) {
                // Invalid/nonobject legacy metadata retains ordinary kind and its original bytes.
            }
        });
        for (String id : braids) jdbc.update("UPDATE " + table + " SET artifact_kind='braid' WHERE id=?", id);
    }

    private IllegalStateException unsupported() {

        return new IllegalStateException("Meld schema has unsupported custom columns/constraints/dependencies; "
                + "restore a verified backup or review a forward migration before starting this version.");
    }
}
