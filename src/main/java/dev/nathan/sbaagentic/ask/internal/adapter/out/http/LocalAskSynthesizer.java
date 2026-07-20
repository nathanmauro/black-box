package dev.nathan.sbaagentic.ask.internal.adapter.out.http;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.ask.AskCitation;
import dev.nathan.sbaagentic.ask.AskComponentStatus;
import dev.nathan.sbaagentic.ask.AskModelProperties;
import dev.nathan.sbaagentic.ask.AskProperties;
import dev.nathan.sbaagentic.ask.internal.application.AskDependencyUnavailable;
import dev.nathan.sbaagentic.ask.internal.application.port.AnswerSynthesizer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class LocalAskSynthesizer implements AnswerSynthesizer {

    private static final int MIN_INPUT_BUDGET = 500;
    private static final String ELISION_MARKER = "\n\n[... memory sources elided to fit local model context ...]\n\n";

    private static final String ASK_SYSTEM = """
            You answer questions from local memory citations only.
            Use the provided numbered memory sources and cite claims with bracketed numbers like [1].
            If the sources do not support an answer, say the answer is not found in memory.
            Do not use outside knowledge.
            """;

    private final AskModelProperties localAi;
    private final AskProperties ask;
    private final RestClient restClient;

    public LocalAskSynthesizer(AskModelProperties localAi, AskProperties ask) {
        this.localAi = localAi;
        this.ask = ask;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(localAi.getTimeout());
        requestFactory.setReadTimeout(localAi.getTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(localAi.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + localAi.getApiKey())
                .build();
    }

    @Override
    public AskComponentStatus status() {
        if (!localAi.isEnabled()) {
            return AskComponentStatus.disabled("local chat disabled");
        }
        try {
            restClient.get().uri("/v1/models").retrieve().toBodilessEntity();
            return AskComponentStatus.available(localAi.getModel());
        }
        catch (RestClientException ex) {
            return AskComponentStatus.unavailable(ex.getMessage());
        }
    }

    @Override
    public String synthesize(String question, List<AskCitation> citations) {
        if (!localAi.isEnabled()) {
            throw new AskDependencyUnavailable("local AI disabled");
        }
        try {
            Map<String, Object> request = Map.of(
                    "model", localAi.getModel(),
                    "temperature", 0.2,
                    "max_tokens", ask.getAnswerMaxTokens(),
                    "stream", false,
                    "messages", List.of(
                            Map.of("role", "system", "content", ASK_SYSTEM),
                            Map.of("role", "user", "content", clampToBudget(
                                    userPrompt(question, citations), localAi.getMaxInputChars()))));
            Map<?, ?> response = restClient.post()
                    .uri(localAi.getChatPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            String content = extractContent(response);
            if (content == null) {
                throw new AskDependencyUnavailable("local AI returned no message content");
            }
            return content;
        }
        catch (RestClientException ex) {
            throw new AskDependencyUnavailable(ex.getMessage());
        }
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

    private static String userPrompt(String question, List<AskCitation> citations) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(question).append("\n\nMemory sources:\n");
        for (AskCitation citation : citations) {
            prompt.append("[")
                    .append(citation.number())
                    .append("] ")
                    .append(citation.title())
                    .append("\n")
                    .append("Source: ")
                    .append(sourceLabel(citation))
                    .append("\n")
                    .append(citation.snippet())
                    .append("\n\n");
        }
        prompt.append("Answer with citations.");
        return prompt.toString();
    }

    private static String clampToBudget(String text, int budget) {
        int effective = Math.max(MIN_INPUT_BUDGET, budget);
        if (text.length() <= effective) {
            return text;
        }
        int keep = effective - ELISION_MARKER.length();
        int head = keep / 2;
        int tail = keep - head;
        return text.substring(0, head) + ELISION_MARKER + text.substring(text.length() - tail);
    }

    private static String sourceLabel(AskCitation citation) {
        if (citation.sessionId() != null && !citation.sessionId().isBlank()) {
            return "Black Box session " + citation.sessionId();
        }
        if (citation.sourcePath() != null && !citation.sourcePath().isBlank()) {
            return citation.sourcePath();
        }
        return citation.source();
    }
}
