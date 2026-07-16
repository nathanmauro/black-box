package dev.nathan.sbaagentic.runner.process;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

@Component
public class RealProcessRunner implements ProcessRunner {

    @Override
    public ProcessResult run(List<String> command, File workingDir, Duration timeout) {
        if (command == null || command.isEmpty()) {
            return new ProcessResult(-1, "", "Command must not be empty", false);
        }

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) {
                builder.directory(workingDir);
            }
            Process started = builder.start();
            process = started;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(started.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(started.getErrorStream()));

            long timeoutMillis = timeout == null ? 30_000 : Math.max(1, timeout.toMillis());
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                return new ProcessResult(-1, stdout.join(), stderr.join(), true);
            }
            return new ProcessResult(process.exitValue(), stdout.join(), stderr.join(), false);
        }
        catch (IOException ex) {
            return new ProcessResult(-1, "", message(ex), false);
        }
        catch (InterruptedException ex) {
            if (process != null) {
                terminate(process);
            }
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "", "Interrupted while waiting for command", false);
        }
        catch (RuntimeException ex) {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            return new ProcessResult(-1, "", message(ex), false);
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
        }
        catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String read(InputStream input) {
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            return message(ex);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
