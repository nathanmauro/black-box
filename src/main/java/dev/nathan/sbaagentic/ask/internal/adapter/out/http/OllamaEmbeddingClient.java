package dev.nathan.sbaagentic.ask.internal.adapter.out.http;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.ask.AskComponentStatus;
import dev.nathan.sbaagentic.ask.internal.application.AskDependencyUnavailable;
import dev.nathan.sbaagentic.ask.internal.application.port.QueryEmbedder;
import dev.nathan.sbaagentic.config.SbaProperties;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OllamaEmbeddingClient implements QueryEmbedder {

    private final SbaProperties.Ask properties;
    private final RestClient restClient;

    public OllamaEmbeddingClient(SbaProperties properties) {
        this.properties = properties.getAsk();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(this.properties.getEmbeddingTimeout());
        requestFactory.setReadTimeout(this.properties.getEmbeddingTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(this.properties.getEmbeddingBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public AskComponentStatus status() {
        if (!properties.isEmbeddingEnabled()) {
            return AskComponentStatus.disabled("embeddings disabled");
        }
        try {
            restClient.get().uri("/").retrieve().toBodilessEntity();
            return AskComponentStatus.available(properties.getEmbeddingModel());
        }
        catch (RestClientException ex) {
            return AskComponentStatus.unavailable(ex.getMessage());
        }
    }

    @Override
    public float[] embed(String query) {
        if (!properties.isEmbeddingEnabled()) {
            throw new AskDependencyUnavailable("embeddings disabled");
        }
        try {
            Map<?, ?> response = restClient.post()
                    .uri(properties.getEmbeddingPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.getEmbeddingModel(), "prompt", query))
                    .retrieve()
                    .body(Map.class);
            float[] embedding = extractEmbedding(response);
            if (embedding.length != properties.getEmbeddingDimensions()) {
                throw new AskDependencyUnavailable("embedding dimensions " + embedding.length
                        + " did not match expected " + properties.getEmbeddingDimensions());
            }
            return embedding;
        }
        catch (RestClientException ex) {
            throw new AskDependencyUnavailable(ex.getMessage());
        }
    }

    private static float[] extractEmbedding(Map<?, ?> response) {
        Object embedding = response == null ? null : response.get("embedding");
        if (embedding instanceof List<?> list) {
            return toFloatArray(list);
        }
        Object embeddings = response == null ? null : response.get("embeddings");
        if (embeddings instanceof List<?> outer && !outer.isEmpty() && outer.getFirst() instanceof List<?> first) {
            return toFloatArray(first);
        }
        throw new AskDependencyUnavailable("embedding response did not include a vector");
    }

    private static float[] toFloatArray(List<?> list) {
        float[] values = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (!(value instanceof Number number)) {
                throw new AskDependencyUnavailable("embedding vector contained a non-number");
            }
            values[i] = number.floatValue();
        }
        return values;
    }
}
