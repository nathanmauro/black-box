package dev.nathan.sbaagentic.project;

import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class BraidDiscoveryPostgresHttpMcpTest extends BraidDiscoveryHttpMcpTest {
    private final BraidPostgresFixture fixture = new BraidPostgresFixture();

    @Override
    protected List<String> databaseArguments() throws Exception {

        return fixture.applicationArguments();
    }

    @Override
    protected List<String> indexNames() {

        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname=current_schema()", String.class);
    }

    @Override
    protected void cleanupDatabase() throws Exception {
        fixture.close();
    }
}
