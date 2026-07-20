package dev.nathan.sbaagentic.platform.internal.config;

import dev.nathan.sbaagentic.ask.AskProperties;
import dev.nathan.sbaagentic.ask.AskModelProperties;
import dev.nathan.sbaagentic.memory.ElasticsearchProperties;
import dev.nathan.sbaagentic.memory.MemoryRetrievalProperties;
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
                    assertThat(context.getBean(AskProperties.class).getMemoryIndex())
                            .isEqualTo("fixture-memory");
                    assertThat(context.getBean(ElasticsearchProperties.class).getIndexName())
                            .isEqualTo("fixture-events");
                    assertThat(context.getBean(IngestionProperties.class).isRedactEnabled()).isFalse();
                });
    }
}
