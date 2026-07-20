package dev.nathan.sbaagentic.ask;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.local-ai")
public class AskModelProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:1234";
    private String chatPath = "/v1/chat/completions";
    private String model = "local-model";
    private String apiKey = "lm-studio";
    private Duration timeout = Duration.ofSeconds(30);
    private int maxInputChars = 8_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getChatPath() { return chatPath; }
    public void setChatPath(String chatPath) { this.chatPath = chatPath; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
}
