package dev.nathan.sbaagentic.architecture;

import dev.nathan.sbaagentic.memory.ElasticsearchProperties;
import dev.nathan.sbaagentic.memory.MemoryRetrievalOperations;
import dev.nathan.sbaagentic.memory.MemoryRetrievalProperties;
import dev.nathan.sbaagentic.project.ProjectMeldSummarizer;
import dev.nathan.sbaagentic.recording.IngestionProperties;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(module = "memory", mode = BootstrapMode.DIRECT_DEPENDENCIES)
@EnableConfigurationProperties({
        ElasticsearchProperties.class,
        IngestionProperties.class,
        MemoryRetrievalProperties.class
})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/memory-module-test.db",
        "sba.elasticsearch.enabled=false"
})
class MemoryApplicationModuleTest {

    @MockitoBean
    ProjectMeldSummarizer meldSummarizer;

    @Autowired
    MemoryRetrievalOperations retrieval;

    @Test
    void exposesTheMemoryRetrievalEntryPoint() {
        assertThat(retrieval).isNotNull();
    }
}
