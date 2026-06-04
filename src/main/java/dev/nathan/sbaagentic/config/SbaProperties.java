package dev.nathan.sbaagentic.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba")
public class SbaProperties {

    private final LocalAi localAi = new LocalAi();

    private final Summary summary = new Summary();

    private final Elasticsearch elasticsearch = new Elasticsearch();

    private final Ingestion ingestion = new Ingestion();

    public LocalAi getLocalAi() {
        return localAi;
    }

    public Summary getSummary() {
        return summary;
    }

    public Elasticsearch getElasticsearch() {
        return elasticsearch;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public static class LocalAi {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:1234";
        private String chatPath = "/v1/chat/completions";
        private String model = "local-model";
        private String apiKey = "lm-studio";
        private Duration timeout = Duration.ofSeconds(30);
        private int maxTokens = 320;
        // Per-request char budget for the local model. 8000 chars ~= 2.7k tokens at the dense ~3 chars/token
        // of a raw transcript; that leaves real headroom on a 4096-token context after the max-tokens output
        // reservation, the system prompt, and chat-template overhead. Larger transcripts are map-reduced, not
        // rejected. Raise this if the loaded model has a bigger context window.
        private int maxInputChars = 8_000;

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

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }
    }

    public static class Summary {
        private String backend = "local";
        private String externalCommand = "";
        private Duration timeout = Duration.ofMinutes(10);

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public String getExternalCommand() {
            return externalCommand;
        }

        public void setExternalCommand(String externalCommand) {
            this.externalCommand = externalCommand;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Elasticsearch {
        private boolean enabled;
        private String baseUrl = "http://localhost:9200";
        private String indexName = "sba-agentic-events";
        private Duration timeout = Duration.ofSeconds(5);

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

        public String getIndexName() {
            return indexName;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Ingestion {
        private int maxTextLength = 20_000;

        public int getMaxTextLength() {
            return maxTextLength;
        }

        public void setMaxTextLength(int maxTextLength) {
            this.maxTextLength = maxTextLength;
        }
    }
}
