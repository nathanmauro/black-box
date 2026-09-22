package dev.nathan.sbaagentic.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.memory.embedding")
public class MemoryEmbeddingProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:11434";
    private String path = "/api/embeddings";
    private String model = "nomic-embed-text";
    private int dimensions = 768;
    private Duration timeout = Duration.ofSeconds(5);
    private String documentPrefix = "search_document: ";
    private String queryPrefix = "search_query: ";

    public boolean isEnabled() {

        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {

        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {

        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getModel() {

        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {

        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public Duration getTimeout() {

        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getDocumentPrefix() {

        return documentPrefix;
    }

    public void setDocumentPrefix(String documentPrefix) {
        this.documentPrefix = documentPrefix;
    }

    public String getQueryPrefix() {

        return queryPrefix;
    }

    public void setQueryPrefix(String queryPrefix) {
        this.queryPrefix = queryPrefix;
    }
}
