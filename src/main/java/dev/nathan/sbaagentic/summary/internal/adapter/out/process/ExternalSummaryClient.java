package dev.nathan.sbaagentic.summary.internal.adapter.out.process;

import dev.nathan.sbaagentic.summary.SummaryProperties;
import dev.nathan.sbaagentic.summary.internal.application.port.ExternalSummaryModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ExternalSummaryClient implements ExternalSummaryModel {

    private final SummaryProperties properties;

    public ExternalSummaryClient(SummaryProperties properties) {
        this.properties = properties;
    }

    public Optional<String> summarize(String transcript) {
        String command = properties.getExternalCommand();
        if (command == null || command.isBlank()) {

            return Optional.empty();
        }

        Process process = null;
        try {
            Process started = new ProcessBuilder("/bin/sh", "-c", command).start();
            process = started;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(started.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(started.getErrorStream()));

            try (var stdin = started.getOutputStream()) {
                stdin.write((transcript == null ? "" : transcript).getBytes(StandardCharsets.UTF_8));
            } catch (IOException brokenPipe) {
                // The command may exit or close stdin before the transcript is fully
                // written (fast failure, or a command that never reads stdin). The
                // exit code and stdout below decide the outcome, not this write.
            }

            Duration timeout = properties.getTimeout() == null ? Duration.ofMinutes(10) : properties.getTimeout();
            boolean finished = started.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!finished) {
                stop(started);

                return Optional.empty();
            }
            if (started.exitValue() != 0) {
                stderr.join();

                return Optional.empty();
            }
            String summary = stdout.join().strip();

            return summary.isBlank() ? Optional.empty() : Optional.of(summary);
        } catch (IOException | InterruptedException | UncheckedIOException ex) {
            if (ex instanceof InterruptedException) {
                // Shutdown interrupts a running summary: its command must not outlive the server.
                stop(process);
                Thread.currentThread().interrupt();
            }

            return Optional.empty();
        }
    }

    /**
     * Ends the command and the programs it started. A wrapper script runs its agent CLI as a child,
     * and that child is reparented, not stopped, when only the shell is destroyed.
     */
    private static void stop(Process process) {
        if (process == null)

            return;

        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private static String read(InputStream input) {
        try {

            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
