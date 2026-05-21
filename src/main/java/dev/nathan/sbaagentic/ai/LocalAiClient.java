package dev.nathan.sbaagentic.ai;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class LocalAiClient {

    private final SbaProperties.LocalAi properties;
    private final RestClient restClient;

    public LocalAiClient(SbaProperties properties) {
        this.properties = properties.getLocalAi();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(this.properties.getTimeout());
        requestFactory.setReadTimeout(this.properties.getTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(this.properties.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.properties.getApiKey())
                .build();
    }

    public AiHealth health() {
        if (!properties.isEnabled()) {
            return new AiHealth(false, false, properties.getModel(), "disabled");
        }
        try {
            restClient.get().uri("/v1/models").retrieve().toBodilessEntity();
            return new AiHealth(true, true, properties.getModel(), "reachable");
        }
        catch (RestClientException ex) {
            return new AiHealth(true, false, properties.getModel(), ex.getMessage());
        }
    }

    public String summarize(String text) {
        if (!properties.isEnabled()) {
            return fallbackSummary(text);
        }
        try {
            Map<String, Object> request = Map.of(
                    "model", properties.getModel(),
                    "temperature", 0.2,
                    "max_tokens", properties.getMaxTokens(),
                    "stream", false,
                    "messages", List.of(
                            Map.of(
                                    "role", "system",
                                    "content", "Summarize this local agent session for later recall. Be concise, factual, and action-oriented."),
                            Map.of("role", "user", "content", text)));

            Map<?, ?> response = restClient.post()
                    .uri(properties.getChatPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            Object choices = response == null ? null : response.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
                Object message = first.get("message");
                if (message instanceof Map<?, ?> messageMap) {
                    Object content = messageMap.get("content");
                    if (content instanceof String value && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        catch (RestClientException ex) {
            return fallbackSummary(text) + "\n\nLocal AI unavailable: " + ex.getMessage();
        }
        return fallbackSummary(text);
    }

    private static String fallbackSummary(String text) {
        if (text == null || text.isBlank()) {
            return "No session text was available to summarize.";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 497) + "...";
    }
}
