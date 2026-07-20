package dev.nathan.sbaagentic.memory;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.elasticsearch")
public class ElasticsearchProperties {

    private boolean enabled;
    private String baseUrl = "http://localhost:9200";
    private String indexName = "sba-agentic-events";
    private int numberOfReplicas;
    private Duration timeout = Duration.ofSeconds(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public int getNumberOfReplicas() { return numberOfReplicas; }
    public void setNumberOfReplicas(int numberOfReplicas) { this.numberOfReplicas = Math.max(0, numberOfReplicas); }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
}
