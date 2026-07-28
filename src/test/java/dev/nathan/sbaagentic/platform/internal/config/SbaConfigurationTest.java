package dev.nathan.sbaagentic.platform.internal.config;

import dev.nathan.sbaagentic.ask.AskProperties;
import dev.nathan.sbaagentic.ask.AskModelProperties;
import dev.nathan.sbaagentic.memory.ElasticsearchProperties;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.MemoryRecallProperties;
import dev.nathan.sbaagentic.memory.MemoryRetrievalProperties;
import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.summary.SummaryExportProperties;
import dev.nathan.sbaagentic.summary.SummaryModelProperties;
import dev.nathan.sbaagentic.summary.SummaryProperties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SbaConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SbaConfiguration.class);

    @Test
    void bindsExternalSummaryBackendByDefault() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("sba.summary.backend")).isEqualTo("external");
            assertThat(context.getBean(SummaryProperties.class).getBackend()).isEqualTo("external");
            assertThat(context.getBean(AskProperties.class).getDefaultAskCitations()).isEqualTo(6);
            assertThat(context.getBean(ElasticsearchProperties.class).getIndexName())
                    .isEqualTo("sba-agentic-events");
            assertThat(context.getBean(IngestionProperties.class).getMaxTextLength()).isEqualTo(20_000);
            assertThat(context.getBean(SummaryModelProperties.class).getModel()).isEqualTo("local-model");
            assertThat(context.getBean(AskModelProperties.class).getModel()).isEqualTo("local-model");
            assertThat(context.getBean(MemoryRetrievalProperties.class).getMemoryIndex())
                    .isEqualTo("agent-memory");
            assertThat(context.getBean(MemoryEmbeddingProperties.class).getModel())
                    .isEqualTo("nomic-embed-text");
            assertThat(context.getBean(MemoryEmbeddingProperties.class).getDimensions()).isEqualTo(768);
            assertThat(context.getBean(MemoryEmbeddingProperties.class).getDocumentPrefix())
                    .isEqualTo("search_document: ");
            assertThat(context.getBean(MemoryEmbeddingProperties.class).getQueryPrefix())
                    .isEqualTo("search_query: ");
            assertThat(context.getBean(MemoryRecallProperties.class).getRelevanceFloor())
                    .isEqualTo(0.61);
            assertThat(context.getBean(MemoryVectorProperties.class).getSqliteVecPath()).isEmpty();
            assertThat(context.getBean(SummaryExportProperties.class).getTargets())
                    .singleElement()
                    .extracting(SummaryExportProperties.Target::getId)
                    .isEqualTo("obsidian");
        });
    }

    @Test
    void bindsExplicitLocalSummaryBackendOverride() {
        contextRunner
                .withPropertyValues("SBA_SUMMARY_BACKEND=local")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("sba.summary.backend")).isEqualTo("local");
                    assertThat(context.getBean(SummaryProperties.class).getBackend()).isEqualTo("local");
                });
    }

    @Test
    void preservesEnvironmentBindingAcrossModuleOwnedProperties() {
        contextRunner
                .withPropertyValues(
                        "SBA_LOCAL_AI_MODEL=fixture-model",
                        "SBA_MEMORY_EMBEDDING_MODEL=fixture-embedding",
                        "SBA_MEMORY_EMBEDDING_ENABLED=false",
                        "SBA_MEMORY_EMBEDDING_DOCUMENT_PREFIX=doc:",
                        "SBA_MEMORY_EMBEDDING_QUERY_PREFIX=query:",
                        "SBA_MEMORY_RECALL_RELEVANCE_FLOOR=0.42",
                        "SBA_SQLITE_VEC_PATH=/tmp/fixture-vec0.dylib",
                        "SBA_ASK_MEMORY_INDEX=fixture-memory",
                        "SBA_ELASTICSEARCH_INDEX=fixture-events",
                        "SBA_REDACT_ENABLED=false")
                .run(context -> {
                    assertThat(context.getBean(SummaryModelProperties.class).getModel())
                            .isEqualTo("fixture-model");
                    assertThat(context.getBean(AskModelProperties.class).getModel())
                            .isEqualTo("fixture-model");
                    assertThat(context.getBean(MemoryRetrievalProperties.class).getMemoryIndex())
                            .isEqualTo("fixture-memory");
                    assertThat(context.getBean(MemoryEmbeddingProperties.class).getModel())
                            .isEqualTo("fixture-embedding");
                    assertThat(context.getBean(MemoryEmbeddingProperties.class).isEnabled()).isFalse();
                    assertThat(context.getBean(MemoryEmbeddingProperties.class).getDocumentPrefix())
                            .isEqualTo("doc:");
                    assertThat(context.getBean(MemoryEmbeddingProperties.class).getQueryPrefix())
                            .isEqualTo("query:");
                    assertThat(context.getBean(MemoryRecallProperties.class).getRelevanceFloor())
                            .isEqualTo(0.42);
                    assertThat(context.getBean(MemoryVectorProperties.class).getSqliteVecPath())
                            .isEqualTo("/tmp/fixture-vec0.dylib");
                    assertThat(context.getBean(AskProperties.class).getMemoryIndex())
                            .isEqualTo("fixture-memory");
                    assertThat(context.getBean(ElasticsearchProperties.class).getIndexName())
                            .isEqualTo("fixture-events");
                    assertThat(context.getBean(IngestionProperties.class).isRedactEnabled()).isFalse();
                });
    }
}
