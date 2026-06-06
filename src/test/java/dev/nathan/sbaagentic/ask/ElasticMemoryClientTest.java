package dev.nathan.sbaagentic.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.config.SbaProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticMemoryClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsBm25QueryAgainstMemoryTextAndMetadataFields() throws Exception {
        SbaProperties properties = new SbaProperties();

        String json = objectMapper.writeValueAsString(
                ElasticMemoryClient.bm25Query("agent memory", 7, properties.getAsk()));

        assertThat(json)
                .contains("\"size\":7")
                .contains("\"multi_match\"")
                .contains("\"query\":\"agent memory\"")
                .contains("\"fields\"")
                .contains("text^4")
                .contains("title^3")
                .contains("source_path^2")
                .contains("\"highlight\"");
    }

    @Test
    void buildsKnnQueryWithConfiguredVectorFieldAndDimensions() throws Exception {
        SbaProperties properties = new SbaProperties();
        properties.getAsk().setVectorField("memory_vector");

        String json = objectMapper.writeValueAsString(
                ElasticMemoryClient.knnQuery(new float[] { 0.1f, 0.2f, 0.3f }, 5, properties.getAsk()));

        assertThat(json)
                .contains("\"size\":5")
                .contains("\"knn\"")
                .contains("\"field\":\"memory_vector\"")
                .contains("\"query_vector\":[0.1,0.2,0.3]")
                .contains("\"k\":5")
                .contains("\"num_candidates\"");
    }
}
