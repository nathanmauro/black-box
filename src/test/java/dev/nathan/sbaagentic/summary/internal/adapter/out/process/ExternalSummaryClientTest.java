package dev.nathan.sbaagentic.summary.internal.adapter.out.process;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.summary.SummaryProperties;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Run the configured external summary command")
class ExternalSummaryClientTest {

    @Test
    @DisplayName("Pass the full transcript through stdin and collect the shell command output")
    void sendsTranscriptThroughConfiguredShellCommand() {
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("cat | sed 's/^/posix: /'");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        Optional<String> summary = client.summarize("Session: test\n\n[UserPromptSubmit] explain the delay");

        assertThat(summary).contains("posix: Session: test\nposix: \nposix: [UserPromptSubmit] explain the delay");
    }

    @Test
    @DisplayName("Whitespace-only output provides no summary")
    void returnsEmptyWhenCommandOutputsNoUsableSummary() {
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("printf '   '");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        assertThat(client.summarize("anything")).isEmpty();
    }

    @Test
    @DisplayName("A missing command fails without inventing a summary")
    void missingCommandFailsCleanly() {
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("/definitely/not/a/real/sba-summary-command");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        assertThat(client.summarize("anything")).isEmpty();
    }
}
