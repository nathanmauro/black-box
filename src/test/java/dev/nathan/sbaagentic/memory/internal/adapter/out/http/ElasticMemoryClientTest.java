package dev.nathan.sbaagentic.memory.internal.adapter.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.MemoryRetrievalProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticMemoryClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsBm25QueryAgainstMemoryTextAndMetadataFields() throws Exception {
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties();

        String json = objectMapper.writeValueAsString(
                ElasticMemoryClient.bm25Query("agent memory", 7, properties));

        assertThat(json)
                .contains("\"size\":7")
                .contains("\"multi_match\"")
                .contains("\"query\":\"agent memory\"")
                .contains("\"fields\"")
                .contains("text^4")
                .contains("title^3")
                .contains("source_path^2")
                .contains("sourcePath^2")
                .contains("corpus")
                .contains("project")
                .contains("\"highlight\"");
    }

    @Test
    void buildsKnnQueryWithConfiguredVectorFieldAndDimensions() throws Exception {
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties();
        properties.setVectorField("memory_vector");

        String json = objectMapper.writeValueAsString(
                ElasticMemoryClient.knnQuery(new float[] { 0.1f, 0.2f, 0.3f }, 5, properties));

        assertThat(json)
                .contains("\"size\":5")
                .contains("\"knn\"")
                .contains("\"field\":\"memory_vector\"")
                .contains("\"query_vector\":[0.1,0.2,0.3]")
                .contains("\"k\":5")
                .contains("\"num_candidates\"");
    }

    @Test
    void defaultsToAskMyHistoryAgentMemoryVectorField() {
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties();

        assertThat(properties.getVectorField()).isEqualTo("vector");
    }

    @Test
    void mapsChronicleMetadataFromAskMyHistoryHits() {
        var response = java.util.Map.of(
                "hits", java.util.Map.of(
                        "hits", java.util.List.of(java.util.Map.of(
                                "_id", "chronicle-1",
                                "_score", 9.5,
                                "_source", java.util.Map.of(
                                        "corpus", "chronicle",
                                        "project", "chronicle",
                                        "sourcePath", "/home/user/agent-memory/notes/summary.md",
                                        "ts", "2026-06-06T04:51:00.000Z",
                                        "title", "Memory summary",
                                        "text", "Chronicle memory text")))));

        var hits = ElasticMemoryClient.mapHits(response);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().source()).isEqualTo("chronicle");
        assertThat(hits.getFirst().sourcePath()).endsWith("summary.md");
        assertThat(hits.getFirst().timestamp()).isEqualTo("2026-06-06T04:51:00.000Z");
        assertThat(hits.getFirst().title()).isEqualTo("Memory summary");
        assertThat(hits.getFirst().text()).isEqualTo("Chronicle memory text");
    }
}
