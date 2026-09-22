package dev.nathan.sbaagentic.judgment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.judge")
public class JudgmentProperties {

    private boolean enabled = false;
    private String provider = "jev";
    private int maxOthers = 8;
    private long timeoutMs = 12_000;
    private final Beat beat = new Beat();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getMaxOthers() {
        return maxOthers;
    }

    public void setMaxOthers(int maxOthers) {
        this.maxOthers = maxOthers;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Beat getBeat() {
        return beat;
    }

    public static class Beat {
        private long gapMs = 4_000;
        private int maxEvents = 12;
        private int maxChars = 1_500;

        public long getGapMs() {
            return gapMs;
        }

        public void setGapMs(long gapMs) {
            this.gapMs = gapMs;
        }

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }
    }
}
