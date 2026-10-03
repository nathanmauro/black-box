package dev.nathan.sbaagentic.project;

import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class ProjectlessBraidPostgresHttpTest extends ProjectlessBraidHttpTest {
    private final BraidPostgresFixture fixture = new BraidPostgresFixture();

    @Override
    protected List<String> databaseArguments() throws Exception {
        prepareLegacyStorage();

        return fixture.applicationArguments();
    }

    @Override
    protected String schemaResource() {

        return "schema-postgres.sql";
    }

    @Override
    protected Connection fixtureConnection() throws Exception {

        return fixture.schemaConnection();
    }

    @Override
    protected void cleanupDatabase() throws Exception {
        fixture.close();
    }

    @Override
    protected void installInputFailure() {
        jdbc.execute(
                "CREATE FUNCTION braid_input_failure_fn() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN IF NEW.input_order=1 THEN RAISE EXCEPTION 'fixture input failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER braid_input_failure BEFORE INSERT ON session_meld_inputs "
                + "FOR EACH ROW EXECUTE FUNCTION braid_input_failure_fn()");
    }

    @Override
    protected void removeInputFailure() {
        jdbc.execute("DROP TRIGGER braid_input_failure ON session_meld_inputs");
        jdbc.execute("DROP FUNCTION braid_input_failure_fn()");
    }
}
