package dev.nathan.sbaagentic.ask;

import java.util.List;

import dev.nathan.sbaagentic.ai.AiHealth;
import dev.nathan.sbaagentic.ai.LocalAiClient;
import dev.nathan.sbaagentic.config.SbaProperties;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
public class LocalAskSynthesizer implements AnswerSynthesizer {

    private static final String ASK_SYSTEM = """
            You answer questions from local memory citations only.
            Use the provided numbered memory sources and cite claims with bracketed numbers like [1].
            If the sources do not support an answer, say the answer is not found in memory.
            Do not use outside knowledge.
            """;

    private final LocalAiClient localAiClient;
    private final SbaProperties.Ask properties;

    public LocalAskSynthesizer(LocalAiClient localAiClient, SbaProperties properties) {
        this.localAiClient = localAiClient;
        this.properties = properties.getAsk();
    }

    @Override
    public AskComponentStatus status() {
        AiHealth health = localAiClient.health();
        if (!health.enabled()) {
            return AskComponentStatus.disabled("local chat disabled");
        }
        if (health.available()) {
            return AskComponentStatus.available(health.model());
        }
        return AskComponentStatus.unavailable(health.detail());
    }

    @Override
    public String synthesize(String question, List<AskCitation> citations) {
        try {
            return localAiClient.complete(ASK_SYSTEM, userPrompt(question, citations), properties.getAnswerMaxTokens());
        }
        catch (RestClientException ex) {
            throw new AskDependencyUnavailable(ex.getMessage());
        }
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
