package dev.nathan.sbaagentic.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opt-in model restatement of continuation prompts. Off by default; when on, the URL the configured
 * local model client posts to ({@code sba.local-ai.chat-path} resolved against {@code base-url}) must
 * be on a loopback host unless non-loopback is allowed.
 */
@ConfigurationProperties(prefix = "sba.continuation.synthesis")
public class ContinuationSynthesisProperties {

    private boolean enabled = false;
    private boolean allowNonLoopback = false;
    private int maxOutputTokens = 512;
    private int maxOutputChars = 4_000;

    public boolean isEnabled() {

        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowNonLoopback() {

        return allowNonLoopback;
    }

    public void setAllowNonLoopback(boolean allowNonLoopback) {
        this.allowNonLoopback = allowNonLoopback;
    }

    public int getMaxOutputTokens() {

        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxOutputChars() {

        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }
}
