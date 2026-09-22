package dev.nathan.sbaagentic.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.ProjectScopeResolver;
import dev.nathan.sbaagentic.recording.TranscriptProperties;
import dev.nathan.sbaagentic.workflow.WorkflowOperations;
import dev.nathan.sbaagentic.workflow.WorkflowPublication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ApplicationModuleTest(module = "workflow", mode = BootstrapMode.DIRECT_DEPENDENCIES)
@EnableConfigurationProperties({IngestionProperties.class, TranscriptProperties.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-workflow-module-test-${random.uuid}.db",
            "sba.memory.embedding.enabled=false"
        })
class WorkflowApplicationModuleTest {

    @MockitoBean
    ProjectScopeResolver projectScopes;

    @MockitoBean
    WorkflowPublication publication;

    @Autowired
    WorkflowOperations workflow;

    @Test
    void exposesTheWorkflowEntryPoint() {
        assertThat(workflow).isNotNull();
    }
}
