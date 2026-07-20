package dev.nathan.sbaagentic.ask.internal.application;

import java.util.List;

import dev.nathan.sbaagentic.ask.AskCitation;
import dev.nathan.sbaagentic.ask.AskComponentStatus;
import dev.nathan.sbaagentic.ask.AskOperations;
import dev.nathan.sbaagentic.ask.AskRequest;
import dev.nathan.sbaagentic.ask.AskResponse;
import dev.nathan.sbaagentic.ask.AskRetrieveResponse;
import dev.nathan.sbaagentic.ask.AskStatus;
import dev.nathan.sbaagentic.ask.internal.application.port.AnswerSynthesizer;
import dev.nathan.sbaagentic.ask.internal.application.port.QueryEmbedder;
import dev.nathan.sbaagentic.ask.internal.domain.ReciprocalRankFusion;
import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.memory.MemoryHit;
import dev.nathan.sbaagentic.memory.MemoryRetrievalOperations;
import dev.nathan.sbaagentic.memory.MemoryRetrievalStatus;

import org.springframework.stereotype.Service;

@Service
public class AskService implements AskOperations {

    public static final String NO_HIT_ANSWER = "Answer not found in memory.";

    private final MemoryRetrievalOperations memoryRetriever;
    private final QueryEmbedder queryEmbedder;
    private final AnswerSynthesizer answerSynthesizer;
    private final SbaProperties.Ask properties;

    public AskService(
            MemoryRetrievalOperations memoryRetriever,
            QueryEmbedder queryEmbedder,
            AnswerSynthesizer answerSynthesizer,
            SbaProperties properties) {
        this.memoryRetriever = memoryRetriever;
        this.queryEmbedder = queryEmbedder;
        this.answerSynthesizer = answerSynthesizer;
        this.properties = properties.getAsk();
    }

    public AskStatus status() {
        AskComponentStatus elastic = componentStatus(memoryRetriever.status());
        AskComponentStatus embeddings = queryEmbedder.status();
        AskComponentStatus chat = answerSynthesizer.status();
        return new AskStatus(
                properties.getMemoryIndex(),
                elastic,
                embeddings,
                chat,
                properties.getEmbeddingModel(),
                properties.getEmbeddingDimensions(),
                properties.getDefaultAskCitations(),
                properties.getDefaultRetrieveResults(),
                retrievalMode(elastic, embeddings));
    }

    public AskRetrieveResponse retrieve(String query, int limit) {
        Retrieval retrieval = retrieveHits(query, safeLimit(limit, properties.getDefaultRetrieveResults()));
        return new AskRetrieveResponse(
                query == null ? "" : query,
                retrieval.mode(),
                retrieval.degraded(),
                retrieval.statusMessage(),
                citations(retrieval.hits()));
    }

    public AskResponse ask(AskRequest request) {
        String question = request == null || request.question() == null ? "" : request.question().trim();
        int limit = request == null ? properties.getDefaultAskCitations()
                : safeLimit(request.limit(), properties.getDefaultAskCitations());
        Retrieval retrieval = retrieveHits(question, limit);
        List<AskCitation> citations = citations(retrieval.hits());
        if (citations.isEmpty()) {
            return new AskResponse(question, NO_HIT_ANSWER, retrieval.mode(),
                    "unavailable".equals(retrieval.mode()), citations);
        }
        try {
            String answer = answerSynthesizer.synthesize(question, citations);
            if (answer == null || answer.isBlank()) {
                throw new AskDependencyUnavailable("empty answer from local chat model");
            }
            return new AskResponse(question, answer.strip(), retrieval.mode(), retrieval.degraded(), citations);
        }
        catch (RuntimeException ex) {
            String answer = "Local answer synthesis is unavailable, but retrieval found "
                    + citations.size() + " memory source(s). Review the citations below.";
            return new AskResponse(question, answer, retrieval.mode(), true, citations);
        }
    }

    private Retrieval retrieveHits(String query, int limit) {
        if (query == null || query.isBlank()) {
            return new Retrieval("empty", false, "empty query", List.of());
        }

        List<MemoryHit> lexical;
        try {
            lexical = memoryRetriever.bm25(query, limit);
        }
        catch (RuntimeException ex) {
            return new Retrieval("unavailable", true, ex.getMessage(), List.of());
        }

        try {
            float[] embedding = queryEmbedder.embed(query);
            List<MemoryHit> vector = memoryRetriever.knn(embedding, limit);
            return new Retrieval("hybrid", false, "hybrid bm25 + vector", ReciprocalRankFusion.fuse(lexical, vector, limit));
        }
        catch (RuntimeException ex) {
            return new Retrieval("bm25", true, ex.getMessage(), lexical.stream().limit(limit).toList());
        }
    }

    private List<AskCitation> citations(List<MemoryHit> hits) {
        java.util.concurrent.atomic.AtomicInteger number = new java.util.concurrent.atomic.AtomicInteger(1);
        return hits.stream()
                .map(hit -> citation(hit, number.getAndIncrement()))
                .toList();
    }

    private static AskCitation citation(MemoryHit hit, int number) {
        return new AskCitation(
                number,
                nullToEmpty(hit.id()),
                fallback(hit.title(), "(untitled memory)"),
                nullToEmpty(hit.source()),
                nullToEmpty(hit.sourcePath()),
                nullToEmpty(hit.sessionId()),
                nullToEmpty(hit.clientSessionId()),
                nullToEmpty(hit.timestamp()),
                fallback(hit.snippet(), clamp(hit.text(), 500)),
                hit.score());
    }

    private static int safeLimit(Integer limit, int defaultLimit) {
        int value = limit == null ? defaultLimit : limit;
        return safeLimit(value, defaultLimit);
    }

    private static int safeLimit(int limit, int defaultLimit) {
        int value = limit <= 0 ? defaultLimit : limit;
        return Math.max(1, Math.min(value, 50));
    }

    private static String retrievalMode(AskComponentStatus elastic, AskComponentStatus embeddings) {
        if (!elastic.available()) {
            return "unavailable";
        }
        return embeddings.available() ? "hybrid" : "bm25";
    }

    private static AskComponentStatus componentStatus(MemoryRetrievalStatus status) {
        return new AskComponentStatus(status.enabled(), status.available(), status.detail());
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private record Retrieval(String mode, boolean degraded, String statusMessage, List<MemoryHit> hits) {
    }
}
