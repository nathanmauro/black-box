package dev.nathan.sbaagentic.project;

import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class SavedMeldReadPoolPostgresHttpTest extends SavedMeldReadPoolHttpTest {
    private final BraidPostgresFixture fixture = new BraidPostgresFixture();

    @Override
    protected List<String> databaseArguments() throws Exception {

        return fixture.applicationArguments();
    }

    @Override
    protected void cleanupDatabase() throws Exception {
        fixture.close();
    }

    /** PostgreSQL rejects a NULL primary key, so only SQLite data can hold a meld without an id. */
    @Override
    void readsKeepWorkingWithAStoredMeldWithoutAnId() {}
}
