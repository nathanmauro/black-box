package dev.nathan.sbaagentic.ai;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.session.Titles;

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
            Map<String, Object> request = chatRequest(properties.getMaxTokens(),
                    "Summarize this local agent session for later recall. Be concise, factual, and action-oriented.",
                    text);
            String content = extractContent(post(request));
            if (content != null) {
                return content;
            }
        }
        catch (RestClientException ex) {
            return fallbackSummary(text) + "\n\nLocal AI unavailable: " + ex.getMessage();
        }
        return fallbackSummary(text);
    }

    public String title(String sourceText) {
        if (!properties.isEnabled()) {
            return fallbackTitle(sourceText);
        }
        try {
            Map<String, Object> request = chatRequest(32,
                    "Write a short, specific title for this local agent session. Use 3 to 8 words. "
                            + "No quotes and no trailing punctuation. Respond with only the title.",
                    sourceText == null ? "" : sourceText);
            String content = extractContent(post(request));
            if (content != null) {
                return Titles.sanitize(stripTitleDecorations(content));
            }
        }
        catch (RestClientException ex) {
            return fallbackTitle(sourceText);
        }
        return fallbackTitle(sourceText);
    }

    private Map<String, Object> chatRequest(int maxTokens, String system, String user) {
        return Map.of(
                "model", properties.getModel(),
                "temperature", 0.2,
                "max_tokens", maxTokens,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
    }

    private Map<?, ?> post(Map<String, Object> request) {
        return restClient.post()
                .uri(properties.getChatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
    }

    private static String extractContent(Map<?, ?> response) {
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
        return null;
    }

    private static String fallbackSummary(String text) {
        if (text == null || text.isBlank()) {
            return "No session text was available to summarize.";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 497) + "...";
    }

    private static String fallbackTitle(String sourceText) {
        return Titles.sanitize(stripTitleDecorations(Titles.firstLine(sourceText)));
    }

    private static String stripTitleDecorations(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        // Local models sometimes wrap the title in quotes or tack on a trailing period.
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
            }
        }
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }
}
