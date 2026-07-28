package dev.nathan.sbaagentic.memory.internal.adapter.out.http;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.internal.application.TextEmbeddingUnavailable;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OllamaTextEmbedderClient implements TextEmbedder {

    private final MemoryEmbeddingProperties properties;
    private final RestClient restClient;

    @Autowired
    public OllamaTextEmbedderClient(MemoryEmbeddingProperties properties) {
        this(properties, restClient(properties));
    }

    OllamaTextEmbedderClient(MemoryEmbeddingProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public EmbeddingVector embedDocument(String text) {
        return embed(withPrefix(properties.getDocumentPrefix(), text));
    }

    @Override
    public EmbeddingVector embedQuery(String text) {
        return embed(withPrefix(properties.getQueryPrefix(), text));
    }

    @Override
    public String documentContentHash(String text) {
        return EmbeddingVector.contentHash(withPrefix(properties.getDocumentPrefix(), text));
    }

    private EmbeddingVector embed(String text) {
        if (!properties.isEnabled()) {
            throw new TextEmbeddingUnavailable("memory embeddings disabled");
        }
        try {
            Map<?, ?> response = restClient.post()
                    .uri(properties.getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.getModel(), "prompt", text))
                    .retrieve()
                    .body(Map.class);
            float[] embedding = extractEmbedding(response);
            if (embedding.length != properties.getDimensions()) {
                throw new TextEmbeddingUnavailable("embedding dimensions " + embedding.length
                        + " did not match expected " + properties.getDimensions());
            }
            return new EmbeddingVector(properties.getModel(), embedding);
        }
        catch (RestClientException ex) {
            throw new TextEmbeddingUnavailable(ex.getMessage(), ex);
        }
    }

    @Override
    public boolean available() {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            restClient.get().uri("/").retrieve().toBodilessEntity();
            return true;
        }
        catch (RestClientException ex) {
            return false;
        }
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    private static RestClient restClient(MemoryEmbeddingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private static String withPrefix(String prefix, String text) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeText = text == null ? "" : text;
        return safePrefix + safeText;
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
        throw new TextEmbeddingUnavailable("embedding response did not include a vector");
    }

    private static float[] toFloatArray(List<?> list) {
        float[] values = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (!(value instanceof Number number)) {
                throw new TextEmbeddingUnavailable("embedding vector contained a non-number");
            }
            values[i] = number.floatValue();
        }
        return values;
    }
}
