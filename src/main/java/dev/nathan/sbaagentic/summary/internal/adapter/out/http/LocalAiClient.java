package dev.nathan.sbaagentic.summary.internal.adapter.out.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.summary.AiHealth;
import dev.nathan.sbaagentic.summary.SummaryModelProperties;
import dev.nathan.sbaagentic.summary.SummaryModelOperations;
import dev.nathan.sbaagentic.summary.internal.application.port.LocalSummaryModel;
import dev.nathan.sbaagentic.recording.Titles;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class LocalAiClient implements LocalSummaryModel, SummaryModelOperations {

    /** Floor for the input budget so a misconfigured tiny value cannot make chunking/clamping degenerate. */
    private static final int MIN_BUDGET = 500;

    private static final String SUMMARY_SYSTEM =
            "Summarize this local agent session for later recall. Be concise, factual, and action-oriented.";

    private static final String ELISION_MARKER =
            "\n\n[... transcript elided to fit local model context ...]\n\n";

    private static final String REDUCE_SYSTEM =
            "Combine these ordered partial summaries of one local agent session into a single concise, "
                    + "factual, action-oriented recap. Do not mention that it was summarized in parts.";

    /** Folding rounds before we give up and clamp; each round shrinks the set by ~budget/part-size. */
    private static final int MAX_REDUCE_ROUNDS = 6;

    private final SummaryModelProperties properties;
    private final RestClient restClient;

    public LocalAiClient(SummaryModelProperties properties) {
        this.properties = properties;
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
        String source = text == null ? "" : text;
        if (source.length() <= budget()) {
            return summarizeOnce(SUMMARY_SYSTEM, source);
        }
        // The transcript is larger than the local model's context window. Map-reduce instead of shipping
        // it whole (which makes the server 400): summarize each window (map), then fold the partial
        // summaries down to one recap (reduce). The fold batches by budget and recurses, so no part is
        // ever dropped — even a session with hundreds of windows collapses faithfully.
        List<String> chunks = splitIntoChunks(source, budget());
        List<String> partials = new ArrayList<>(chunks.size());
        int degraded = 0;
        for (int i = 0; i < chunks.size(); i++) {
            ChunkSummary part = summarizeChunk(chunks.get(i), i + 1, chunks.size());
            if (part.degraded()) {
                degraded++;
            }
            partials.add("Part " + (i + 1) + ": " + part.text());
        }
        String summary = reduceSummaries(partials);
        if (degraded > 0) {
            // Appended outside the model so it cannot be paraphrased away: the reader must see that some
            // segments are raw excerpts because the local model was unreachable for them.
            summary = summary + "\n\n(Note: " + degraded + " of " + chunks.size()
                    + " transcript segments could not reach the local model and were kept as raw excerpts.)";
        }
        return summary;
    }

    /** Fold ordered partial summaries to a single recap, batching by budget and recursing so none drop. */
    private String reduceSummaries(List<String> partials) {
        List<String> current = partials;
        for (int round = 0; round < MAX_REDUCE_ROUNDS; round++) {
            String combined = String.join("\n\n", current);
            if (current.size() <= 1 || combined.length() <= budget()) {
                return summarizeOnce(REDUCE_SYSTEM, combined);
            }
            List<String> batches = batchByBudget(current, budget());
            if (batches.size() >= current.size()) {
                // Cannot fold further (a single part already exceeds budget); the clamp in chatRequest
                // is the floor that still keeps this request from overflowing the context.
                return summarizeOnce(REDUCE_SYSTEM, combined);
            }
            List<String> next = new ArrayList<>(batches.size());
            for (String batch : batches) {
                next.add(summarizeOnce(REDUCE_SYSTEM, batch));
            }
            current = next;
        }
        return summarizeOnce(REDUCE_SYSTEM, String.join("\n\n", current));
    }

    /** Summarize one window of a longer transcript; degrade quietly so the fold still runs. */
    private ChunkSummary summarizeChunk(String chunk, int index, int total) {
        String system = "This is part " + index + " of " + total
                + " of a longer local agent session. Summarize just this part concisely and factually.";
        try {
            String content = extractContent(post(chatRequest(properties.getMaxTokens(), system, chunk)));
            if (content != null) {
                return new ChunkSummary(content, false);
            }
        }
        catch (RestClientException ex) {
            // One unreachable chunk should not sink the whole summary — fall back for this part only.
            return new ChunkSummary(fallbackSummary(chunk), true);
        }
        return new ChunkSummary(fallbackSummary(chunk), true);
    }

    private record ChunkSummary(String text, boolean degraded) {
    }

    /** One user-facing model call, with the offline-degradation note kept for genuine outages. */
    private String summarizeOnce(String system, String text) {
        try {
            String content = extractContent(post(chatRequest(properties.getMaxTokens(), system, text)));
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

    public String complete(String system, String user, int maxTokens) {
        if (!properties.isEnabled()) {
            throw new RestClientException("local AI disabled");
        }
        String content = extractContent(post(chatRequest(maxTokens, system, user == null ? "" : user)));
        if (content == null) {
            throw new RestClientException("local AI returned no message content");
        }
        return content;
    }

    private Map<String, Object> chatRequest(int maxTokens, String system, String user) {
        return Map.of(
                "model", properties.getModel(),
                "temperature", 0.2,
                "max_tokens", maxTokens,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        // Final safety net: even a map-reduce chunk or the reduced partials can never
                        // exceed the budget here, so a single request can no longer overflow the context.
                        Map.of("role", "user", "content", clampToBudget(user, budget()))));
    }

    private int budget() {
        return Math.max(MIN_BUDGET, properties.getMaxInputChars());
    }

    /** Group ordered parts into the fewest contiguous batches whose joined length each stays within budget. */
    static List<String> batchByBudget(List<String> parts, int budget) {
        int effective = Math.max(MIN_BUDGET, budget);
        List<String> batches = new ArrayList<>();
        StringBuilder batch = new StringBuilder();
        for (String part : parts) {
            int joined = batch.length() == 0 ? part.length() : batch.length() + 2 + part.length();
            if (batch.length() > 0 && joined > effective) {
                batches.add(batch.toString());
                batch.setLength(0);
            }
            if (batch.length() > 0) {
                batch.append("\n\n");
            }
            batch.append(part);
        }
        if (batch.length() > 0) {
            batches.add(batch.toString());
        }
        return batches;
    }

    /** Slice text into contiguous windows no larger than {@code max(MIN_BUDGET, budget)}, preferring line breaks. */
    static List<String> splitIntoChunks(String text, int budget) {
        List<String> chunks = new ArrayList<>();
        int effective = Math.max(MIN_BUDGET, budget);
        int len = text.length();
        int pos = 0;
        while (pos < len) {
            int hardEnd = Math.min(pos + effective, len);
            int end = hardEnd;
            if (end < len) {
                // Break on a paragraph/line boundary in the last fifth of the window so we do not cut
                // mid-sentence; a hard cut is fine when no boundary is close. Search below the hard cap
                // so the boundary plus its delimiter still lands within budget.
                int floor = pos + (effective * 4 / 5);
                int para = text.lastIndexOf("\n\n", hardEnd - 2);
                int line = text.lastIndexOf('\n', hardEnd - 1);
                if (para >= floor) {
                    end = para + 2;
                }
                else if (line >= floor) {
                    end = line + 1;
                }
            }
            chunks.add(text.substring(pos, end));
            pos = end;
        }
        return chunks;
    }

    /** Trim to {@code budget}, keeping the head and tail with a visible marker — recall wants both ends. */
    static String clampToBudget(String text, int budget) {
        if (text == null) {
            return "";
        }
        int effective = Math.max(MIN_BUDGET, budget);
        if (text.length() <= effective) {
            return text;
        }
        int keep = effective - ELISION_MARKER.length();
        if (keep <= 0) {
            // Unreachable while MIN_BUDGET (500) > ELISION_MARKER length: defensive guard only.
            return text.substring(0, effective);
        }
        int head = keep / 2;
        int tail = keep - head;
        return text.substring(0, head) + ELISION_MARKER + text.substring(text.length() - tail);
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
