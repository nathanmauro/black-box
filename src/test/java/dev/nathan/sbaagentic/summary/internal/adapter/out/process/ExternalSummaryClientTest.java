package dev.nathan.sbaagentic.summary.internal.adapter.out.process;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.summary.SummaryProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    @DisplayName("An interrupted summary stops the command and the programs it started")
    void interruptionStopsTheCommandAndItsChildren(@TempDir Path directory) throws Exception {
        // Like the bundled wrapper scripts, the shell waits on a child that does the real work.
        Path childPid = directory.resolve("child.pid");
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("sleep 30 & echo $! > '" + childPid + "'; wait");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);
        AtomicReference<Optional<String>> result = new AtomicReference<>();
        Thread summarizer = new Thread(() -> result.set(client.summarize("anything")), "fixture-summarizer");
        summarizer.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(childPid) || Files.readString(childPid).isBlank()) {
            assertThat(System.nanoTime()).as("child started").isLessThan(deadline);
            Thread.sleep(20);
        }
        ProcessHandle child = ProcessHandle.of(
                        Long.parseLong(Files.readString(childPid).strip()))
                .orElseThrow();
        try {
            summarizer.interrupt();
            summarizer.join(Duration.ofSeconds(5));

            assertThat(summarizer.isAlive()).isFalse();
            assertThat(result.get()).isEmpty();
            assertThat(child.onExit()
                            .completeOnTimeout(child, 5, TimeUnit.SECONDS)
                            .get()
                            .isAlive())
                    .as("the command's child process 5 s after the interrupted summary returned")
                    .isFalse();
        } finally {
            child.destroyForcibly(); // only the fixture's own child, whatever the outcome
        }
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
