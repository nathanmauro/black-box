package dev.nathan.sbaagentic.summary;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.summary")
public class SummaryProperties {

    private String backend = "external";
    private String externalCommand = "scripts/summarize-with-codex.sh";
    private Duration timeout = Duration.ofMinutes(10);

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getExternalCommand() { return externalCommand; }
    public void setExternalCommand(String externalCommand) { this.externalCommand = externalCommand; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
}
