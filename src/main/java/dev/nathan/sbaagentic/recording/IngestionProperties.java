package dev.nathan.sbaagentic.recording;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.ingestion")
public class IngestionProperties {

    private int maxTextLength = 20_000;
    private boolean redactEnabled = true;
    private List<String> redactPatterns = new ArrayList<>();

    public int getMaxTextLength() { return maxTextLength; }
    public void setMaxTextLength(int maxTextLength) { this.maxTextLength = maxTextLength; }
    public boolean isRedactEnabled() { return redactEnabled; }
    public void setRedactEnabled(boolean redactEnabled) { this.redactEnabled = redactEnabled; }
    public List<String> getRedactPatterns() { return redactPatterns; }
    public void setRedactPatterns(List<String> redactPatterns) {
        this.redactPatterns = redactPatterns == null ? new ArrayList<>() : redactPatterns;
    }
}
