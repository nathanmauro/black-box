package dev.nathan.sbaagentic.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba")
public class SbaProperties {

    private final LocalAi localAi = new LocalAi();

    private final Elasticsearch elasticsearch = new Elasticsearch();

    private final Ingestion ingestion = new Ingestion();

    public LocalAi getLocalAi() {
        return localAi;
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
