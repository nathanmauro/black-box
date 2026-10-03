package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class MeldSchemaMigrationTest extends MeldMigrationContract {
    @Test
    void attachedIndexesAndTriggersSurviveRebuildAndRecreationFailure() {
        oldSchema();
        seed();
        jdbc.execute("CREATE TABLE audit (meld_id TEXT)");
        String index = "CREATE INDEX custom_meld_title ON session_melds(title)";
        String trigger = "CREATE TRIGGER custom_meld_audit AFTER UPDATE OF title ON session_melds "
                + "BEGIN INSERT INTO audit VALUES(NEW.id); END";
        jdbc.execute(index);
        jdbc.execute(trigger);
        var before = rows();
        var inputs = inputs();
        var definitions = jdbc.queryForList(
                "SELECT type,name,sql FROM sqlite_master WHERE tbl_name='session_melds' ORDER BY type,name");
        JdbcTemplate failing = spy(jdbc);
        doThrow(new DataAccessResourceFailureException("fixture index recreation failure"))
                .when(failing)
                .execute(index);
        assertThatThrownBy(() -> migration(failing).migrate()).isInstanceOf(DataAccessException.class);
        assertThat(rows()).isEqualTo(before);
        assertThat(inputs()).isEqualTo(inputs);
        assertThat(jdbc.queryForList(
                        "SELECT type,name,sql FROM sqlite_master WHERE tbl_name='session_melds' ORDER BY type,name"))
                .isEqualTo(definitions);
        migration(jdbc).migrate();
        assertThat(jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE name='custom_meld_title'", String.class))
                .isEqualTo(index);
        assertThat(jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE name='custom_meld_audit'", String.class))
                .isEqualTo(trigger);
        jdbc.update("UPDATE session_melds SET title=title WHERE id='old-0'");
        assertThat(jdbc.queryForList("SELECT meld_id FROM audit", String.class)).containsExactly("old-0");
        assertThat(rows()).isEqualTo(before);
        assertThat(inputs()).isEqualTo(inputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"column", "check", "unique", "foreign-key", "inbound-foreign-key"})
    void unsupportedLegacyInvariantsFailBeforeChangingAnything(String variant) {
        if (variant.equals("check"))
            jdbc.execute(OLD.replace("body TEXT NOT NULL", "body TEXT NOT NULL CHECK(length(body)>0)"));
        else if (variant.equals("unique"))
            jdbc.execute(OLD.replace("title TEXT NOT NULL", "title TEXT NOT NULL UNIQUE"));
        else if (variant.equals("foreign-key")) {
            jdbc.execute("CREATE TABLE owner (id TEXT PRIMARY KEY)");
            jdbc.execute(OLD.replace("project_key TEXT NOT NULL", "project_key TEXT NOT NULL REFERENCES owner(id)"));
        } else jdbc.execute(OLD);
        jdbc.execute(INPUTS);
        if (variant.equals("column")) jdbc.execute("ALTER TABLE session_melds ADD COLUMN custom_extension TEXT");
        if (variant.equals("inbound-foreign-key"))
            jdbc.execute("CREATE TABLE custom_link (meld_id TEXT REFERENCES session_melds(id))");
        var before = jdbc.queryForList("SELECT type,name,sql FROM sqlite_master ORDER BY type,name");
        assertThatThrownBy(() -> migration(jdbc).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported custom");
        assertThat(jdbc.queryForList("SELECT type,name,sql FROM sqlite_master ORDER BY type,name"))
                .isEqualTo(before);
    }
}
