package dev.nathan.sbaagentic.project;

import java.sql.Connection;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class MeldSchemaPostgresMigrationTest extends MeldMigrationContract {
    private final BraidPostgresFixture fixture = new BraidPostgresFixture();

    @Override
    protected String backend() {

        return "postgres";
    }

    @Override
    protected Connection open() throws Exception {

        return fixture.schemaConnection();
    }

    @Override
    protected void cleanup() throws Exception {
        fixture.close();
    }
}
