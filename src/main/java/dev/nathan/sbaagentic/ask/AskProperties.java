package dev.nathan.sbaagentic.ask;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.ask")
public class AskProperties {

    private String memoryIndex = "agent-memory";
    private boolean embeddingEnabled = true;
    private String embeddingBaseUrl = "http://localhost:11434";
    private String embeddingPath = "/api/embeddings";
    private String embeddingModel = "nomic-embed-text";
    private int embeddingDimensions = 768;
    private Duration embeddingTimeout = Duration.ofSeconds(5);
    private int defaultAskCitations = 6;
    private int defaultRetrieveResults = 10;
    private int answerMaxTokens = 640;

    public String getMemoryIndex() { return memoryIndex; }
    public void setMemoryIndex(String memoryIndex) { this.memoryIndex = memoryIndex; }
    public boolean isEmbeddingEnabled() { return embeddingEnabled; }
    public void setEmbeddingEnabled(boolean embeddingEnabled) { this.embeddingEnabled = embeddingEnabled; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }
    public String getEmbeddingPath() { return embeddingPath; }
    public void setEmbeddingPath(String embeddingPath) { this.embeddingPath = embeddingPath; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(int embeddingDimensions) { this.embeddingDimensions = embeddingDimensions; }
    public Duration getEmbeddingTimeout() { return embeddingTimeout; }
    public void setEmbeddingTimeout(Duration embeddingTimeout) { this.embeddingTimeout = embeddingTimeout; }
    public int getDefaultAskCitations() { return defaultAskCitations; }
    public void setDefaultAskCitations(int defaultAskCitations) { this.defaultAskCitations = defaultAskCitations; }
    public int getDefaultRetrieveResults() { return defaultRetrieveResults; }
    public void setDefaultRetrieveResults(int defaultRetrieveResults) { this.defaultRetrieveResults = defaultRetrieveResults; }
    public int getAnswerMaxTokens() { return answerMaxTokens; }
    public void setAnswerMaxTokens(int answerMaxTokens) { this.answerMaxTokens = answerMaxTokens; }
}
