package dev.nathan.sbaagentic.project;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.editor")
public class EditorProperties {

    private boolean enabled = true;
    private String command = "/Applications/Cursor.app/Contents/Resources/app/bin/code";
    private List<String> allowlist = new ArrayList<>(List.of(
            "/Applications/Cursor.app/Contents/Resources/app/bin/code",
            "/opt/homebrew/bin/cursor",
            "/usr/local/bin/cursor",
            "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code",
            "/opt/homebrew/bin/code"));
    private Duration timeout = Duration.ofSeconds(5);

    public boolean isEnabled() {

        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCommand() {

        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getAllowlist() {

        return allowlist;
    }

    public void setAllowlist(List<String> allowlist) {
        this.allowlist = allowlist == null ? new ArrayList<>() : new ArrayList<>(allowlist);
    }

    public Duration getTimeout() {

        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
