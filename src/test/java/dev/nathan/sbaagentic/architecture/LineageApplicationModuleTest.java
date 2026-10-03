package dev.nathan.sbaagentic.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.lineage.SessionLineageOperations;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.ProjectScopeResolver;
import dev.nathan.sbaagentic.recording.TranscriptProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ApplicationModuleTest(module = "lineage", mode = BootstrapMode.DIRECT_DEPENDENCIES)
@EnableConfigurationProperties({IngestionProperties.class, TranscriptProperties.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-lineage-module-test-${random.uuid}.db",
            "sba.memory.embedding.enabled=false"
        })
class LineageApplicationModuleTest {

    @MockitoBean
    ProjectScopeResolver projectScopes;

    @Autowired
    SessionLineageOperations lineage;

    @Test
    void exposesTheLineageEntryPoint() {
        assertThat(lineage).isNotNull();
    }
}
