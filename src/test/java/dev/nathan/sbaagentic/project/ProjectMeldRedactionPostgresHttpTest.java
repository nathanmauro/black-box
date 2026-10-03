package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class ProjectMeldRedactionPostgresHttpTest extends ProjectMeldRedactionHttpTest {
    private final List<BraidPostgresFixture> fixtures = new ArrayList<>();
    private final List<String> ownedSchemas = new ArrayList<>();

    @Override
    protected List<String> databaseArguments(Path database) throws Exception {
        BraidPostgresFixture fixture = new BraidPostgresFixture();
        fixtures.add(fixture);
        try (var connection = fixture.schemaConnection()) {
            ownedSchemas.add(connection.getSchema());
        }

        return fixture.applicationArguments();
    }

    @Override
    protected void cleanupDatabases() throws Exception {
        for (BraidPostgresFixture fixture : fixtures) fixture.close();
        if (!fixtures.isEmpty()) {
            try (var connection = fixtures.getFirst().connection();
                    var query = connection.prepareStatement("SELECT count(*) FROM pg_namespace WHERE nspname=?")) {
                for (String schema : ownedSchemas) {
                    query.setString(1, schema);
                    try (var rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1))
                                .as("owned schema removed: %s", schema)
                                .isZero();
                    }
                }
            }
        }
    }

    @Override
    protected void installInputFailure(JdbcTemplate jdbc) {
        jdbc.execute(
                "CREATE FUNCTION redaction_input_failure_fn() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN IF NEW.input_order=1 THEN RAISE EXCEPTION 'fixture input failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER redaction_input_failure BEFORE INSERT ON session_meld_inputs "
                + "FOR EACH ROW EXECUTE FUNCTION redaction_input_failure_fn()");
    }

    @Override
    protected void removeInputFailure(JdbcTemplate jdbc) {
        jdbc.execute("DROP TRIGGER redaction_input_failure ON session_meld_inputs");
        jdbc.execute("DROP FUNCTION redaction_input_failure_fn()");
    }
}
