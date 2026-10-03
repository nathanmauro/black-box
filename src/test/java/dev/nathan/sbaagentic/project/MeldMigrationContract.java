package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.project.internal.adapter.out.sqlite.MeldSchemaMigration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

abstract class MeldMigrationContract {
    static final String OLD =
            "CREATE TABLE session_melds (id TEXT PRIMARY KEY, project_key TEXT NOT NULL, title TEXT NOT NULL, "
                    + "body TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, prompt_version TEXT NOT NULL, "
                    + "execution_mode TEXT NOT NULL, saved_from_preview INTEGER NOT NULL, metadata_json TEXT, created_at TEXT NOT NULL)";
    static final String INPUTS = "CREATE TABLE session_meld_inputs (meld_id TEXT NOT NULL, session_id TEXT NOT NULL, "
            + "input_order INTEGER NOT NULL, included_summary INTEGER NOT NULL, metadata_json TEXT, PRIMARY KEY(meld_id,session_id))";
    static final String OLD_FIELDS =
            "id,project_key,title,body,provider,model,prompt_version,execution_mode,saved_from_preview,metadata_json,created_at";

    @TempDir
    Path temp;

    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;

    protected String backend() {

        return "sqlite";
    }

    protected Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve(UUID.randomUUID() + ".db"));
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
        }

        return connection;
    }

    protected void cleanup() throws Exception {}

    @BeforeEach
    void setup() throws Exception {
        dataSource = new SingleConnectionDataSource(open(), true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (dataSource != null) dataSource.destroy();
        } finally {
            cleanup();
        }
    }

    MeldSchemaMigration migration(JdbcTemplate template) {

        return new MeldSchemaMigration(
                template, new ObjectMapper(), new DataSourceTransactionManager(dataSource), backend());
    }

    void oldSchema() {
        jdbc.execute(OLD);
        jdbc.execute(INPUTS);
    }

    void seed() {
        String[] metadata = {
            "{ \"kind\" : \"braid\", \"nullable\":null }",
            "{\"kind\":\"Braid\"}",
            "[1,null]",
            "invalid-json",
            null,
            "{\"kind\":\"braid\"} false",
            "{\"kind\":\"braid\"} invalid-json"
        };
        for (int i = 0; i < metadata.length; i++) {
            jdbc.update(
                    "INSERT INTO session_melds VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    "old-" + i,
                    "/fixture/old-project",
                    "Old title " + i,
                    "Old body \n",
                    "local",
                    "context-bundle",
                    "project-meld-v1",
                    "export_bundle",
                    1,
                    metadata[i],
                    i % 2 == 0 ? "2026-01-01T00:00:00Z" : "2026-01-01T00:00:00.000000001Z");
        }
        jdbc.update(
                "INSERT INTO session_meld_inputs VALUES(?,?,?,?,?)",
                "old-0",
                "orphan-session-b",
                9,
                0,
                "{\"arbitrary\":null}");
        jdbc.update(
                "INSERT INTO session_meld_inputs VALUES(?,?,?,?,?)",
                "old-0",
                "orphan-session-a",
                3,
                1,
                "legacy opaque text");
    }

    List<Map<String, Object>> rows() {

        return jdbc.queryForList("SELECT " + OLD_FIELDS + " FROM session_melds ORDER BY id");
    }

    List<Map<String, Object>> inputs() {

        return jdbc.queryForList("SELECT * FROM session_meld_inputs ORDER BY meld_id,input_order");
    }

    @Test
    void oldRowsAndOrphanInputsSurviveUpgradeAndRepeatExactly() {
        oldSchema();
        seed();
        var rows = rows();
        var inputs = inputs();
        migration(jdbc).migrate();
        assertThat(rows()).isEqualTo(rows);
        assertThat(inputs()).isEqualTo(inputs);
        assertThat(jdbc.queryForList("SELECT artifact_kind FROM session_melds ORDER BY id", String.class))
                .containsExactly("braid", "meld", "meld", "meld", "meld", "meld", "meld");
        migration(jdbc).migrate();
        assertThat(rows()).isEqualTo(rows);
        assertThat(inputs()).isEqualTo(inputs);
        assertConstraints("old-0", "old-1");
    }

    void assertConstraints(String braid, String ordinary) {
        assertThatThrownBy(() -> jdbc.update("UPDATE session_melds SET project_key=NULL WHERE id=?", ordinary))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE session_melds SET artifact_kind='other' WHERE id=?", braid))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE session_melds SET artifact_kind=NULL WHERE id=?", braid))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE session_melds SET project_key=NULL WHERE id=?", braid);
        assertThat(jdbc.queryForObject("SELECT project_key FROM session_melds WHERE id=?", String.class, braid))
                .isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE session_melds SET artifact_kind='meld' WHERE id=?", braid))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void freshSchemaAlsoEnforcesOwnershipAndRepeatStartup() {
        new ResourceDatabasePopulator(
                        new ClassPathResource(backend().equals("postgres") ? "schema-postgres.sql" : "schema.sql"))
                .execute(dataSource);
        migration(jdbc).migrate();
        for (String kind : List.of("braid", "meld")) {
            jdbc.update(
                    "INSERT INTO session_melds (" + OLD_FIELDS + ",artifact_kind) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    kind,
                    "/fixture/project",
                    "Title",
                    "Body",
                    "local",
                    "none",
                    "v1",
                    "export_bundle",
                    1,
                    "{}",
                    "2026-01-01T00:00:00Z",
                    kind);
        }
        migration(jdbc).migrate();
        assertConstraints("braid", "meld");
    }

    @Test
    void lateMigrationFailureRollsBackOldShapeRowsAndInputs() {
        oldSchema();
        seed();
        var rows = rows();
        var inputs = inputs();
        JdbcTemplate failing = spy(jdbc);
        String statement = backend().equals("postgres")
                ? "ALTER TABLE session_melds ALTER COLUMN project_key DROP NOT NULL"
                : "ALTER TABLE session_melds_braid_upgrade RENAME TO session_melds";
        doThrow(new DataAccessResourceFailureException("fixture migration failure"))
                .when(failing)
                .execute(statement);
        assertThatThrownBy(() -> migration(failing).migrate()).isInstanceOf(DataAccessException.class);
        assertThat(rows()).isEqualTo(rows);
        assertThat(inputs()).isEqualTo(inputs);
        assertThatThrownBy(() -> jdbc.queryForList("SELECT artifact_kind FROM session_melds"))
                .isInstanceOf(DataAccessException.class);
        migration(jdbc).migrate();
        assertThat(rows()).isEqualTo(rows);
        assertThat(inputs()).isEqualTo(inputs);
    }
}
