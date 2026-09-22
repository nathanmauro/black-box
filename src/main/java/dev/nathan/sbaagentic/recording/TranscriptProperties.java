package dev.nathan.sbaagentic.recording;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.transcript")
public class TranscriptProperties {

    private List<String> codexRoots = defaultRoots(".codex", "sessions");
    private List<String> claudeRoots = defaultRoots(".claude", "projects");
    private long maxBytes = 128L * 1024L * 1024L;
    private int cacheEntries = 8;

    public List<String> getCodexRoots() {

        return codexRoots;
    }

    public void setCodexRoots(List<String> roots) {
        this.codexRoots = roots == null ? new ArrayList<>() : new ArrayList<>(roots);
    }

    public List<String> getClaudeRoots() {

        return claudeRoots;
    }

    public void setClaudeRoots(List<String> roots) {
        this.claudeRoots = roots == null ? new ArrayList<>() : new ArrayList<>(roots);
    }

    public long getMaxBytes() {

        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getCacheEntries() {

        return cacheEntries;
    }

    public void setCacheEntries(int cacheEntries) {
        this.cacheEntries = cacheEntries;
    }

    private static List<String> defaultRoots(String first, String second) {
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {

            return new ArrayList<>();
        }

        return new ArrayList<>(List.of(Path.of(home, first, second).toString()));
    }
}
