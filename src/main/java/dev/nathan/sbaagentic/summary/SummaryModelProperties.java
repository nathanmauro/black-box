package dev.nathan.sbaagentic.summary;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.DefaultUriBuilderFactory;

@ConfigurationProperties(prefix = "sba.local-ai")
public class SummaryModelProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:1234";
    private String chatPath = "/v1/chat/completions";
    private String model = "local-model";
    private String apiKey = "lm-studio";
    private Duration timeout = Duration.ofSeconds(30);
    private int maxTokens = 320;
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

    /**
     * The URI chat completions are posted to: {@code chat-path} resolved against {@code base-url} the
     * way a {@code RestClient} with that base URL resolves a request URI. An absolute chat path, with
     * a scheme or a leading {@code //}, names its own host and replaces the base URL's.
     *
     * @throws RuntimeException when the two values do not form a URI
     */
    public URI chatUri() {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(baseUrl);
        URI uri = factory.expand(chatPath);

        return uri.isAbsolute() ? uri : factory.expand("").resolve(uri);
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
