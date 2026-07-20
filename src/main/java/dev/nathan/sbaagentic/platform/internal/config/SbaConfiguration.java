package dev.nathan.sbaagentic.platform.internal.config;

import dev.nathan.sbaagentic.ask.AskModelProperties;
import dev.nathan.sbaagentic.ask.AskProperties;
import dev.nathan.sbaagentic.memory.ElasticsearchProperties;
import dev.nathan.sbaagentic.memory.MemoryRetrievalProperties;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.summary.SummaryExportProperties;
import dev.nathan.sbaagentic.summary.SummaryModelProperties;
import dev.nathan.sbaagentic.summary.SummaryProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AskModelProperties.class,
        AskProperties.class,
        ElasticsearchProperties.class,
        IngestionProperties.class,
        MemoryRetrievalProperties.class,
        SummaryExportProperties.class,
        SummaryModelProperties.class,
        SummaryProperties.class
})
public class SbaConfiguration {
}
