package dev.nathan.sbaagentic.architecture;

import dev.nathan.sbaagentic.memory.ElasticsearchProperties;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.MemoryRecallProperties;
import dev.nathan.sbaagentic.memory.MemoryRetrievalOperations;
import dev.nathan.sbaagentic.memory.MemoryRetrievalProperties;
import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
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
        MemoryEmbeddingProperties.class,
        MemoryRecallProperties.class,
        MemoryRetrievalProperties.class,
        MemoryVectorProperties.class
})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/memory-module-test-${random.uuid}.db",
        "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false"
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
