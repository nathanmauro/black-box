package dev.nathan.sbaagentic.architecture;

import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.ProjectScopeResolver;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(module = "recording")
@EnableConfigurationProperties(IngestionProperties.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/recording-module-test.db")
class RecordingApplicationModuleTest {

    @MockitoBean
    ProjectScopeResolver projectScopes;

    @Autowired
    EventRecorder recorder;

    @Test
    void exposesTheRecordingEntryPoint() {
        assertThat(recorder).isNotNull();
    }
}
