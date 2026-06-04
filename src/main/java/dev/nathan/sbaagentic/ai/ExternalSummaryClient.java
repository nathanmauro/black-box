package dev.nathan.sbaagentic.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.springframework.stereotype.Component;

@Component
public class ExternalSummaryClient {

    private final SbaProperties.Summary properties;

    public ExternalSummaryClient(SbaProperties properties) {
        this.properties = properties.getSummary();
    }

    public Optional<String> summarize(String transcript) {
        String command = properties.getExternalCommand();
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }

        try {
            Process process = new ProcessBuilder("/bin/zsh", "-lc", command).start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));

            try (var stdin = process.getOutputStream()) {
                stdin.write((transcript == null ? "" : transcript).getBytes(StandardCharsets.UTF_8));
            }

            Duration timeout = properties.getTimeout() == null ? Duration.ofMinutes(10) : properties.getTimeout();
            boolean finished = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                stderr.join();
                return Optional.empty();
            }
            String summary = stdout.join().strip();
            return summary.isBlank() ? Optional.empty() : Optional.of(summary);
        }
        catch (IOException | InterruptedException | UncheckedIOException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private static String read(InputStream input) {
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
