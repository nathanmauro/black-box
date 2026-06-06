package dev.nathan.sbaagentic.ask;

public record AskStatus(
        String memoryIndex,
        AskComponentStatus elasticsearch,
        AskComponentStatus embeddings,
        AskComponentStatus chat,
        String embeddingModel,
        int embeddingDimensions,
        int defaultAskCitations,
        int defaultRetrieveResults,
        String retrievalMode) {
}
