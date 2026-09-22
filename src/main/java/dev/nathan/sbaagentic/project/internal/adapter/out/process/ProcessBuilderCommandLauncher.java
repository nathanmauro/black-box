package dev.nathan.sbaagentic.project.internal.adapter.out.process;

import dev.nathan.sbaagentic.project.internal.application.port.CommandLaunchException;
import dev.nathan.sbaagentic.project.internal.application.port.CommandLauncher;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ProcessBuilderCommandLauncher implements CommandLauncher {

    @Override
    public void launch(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            throw new CommandLaunchException("Command is required.");
        }
        Process process = null;
        try {
            process = new ProcessBuilder(List.copyOf(command))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long timeoutMillis = timeout == null ? 1L : Math.max(1L, timeout.toMillis());
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                if (!stop(process)) {
                    throw new CommandLaunchException("Command timed out and could not be stopped.");
                }
                throw new CommandLaunchException("Command timed out.");
            }
            if (process.exitValue() != 0) {
                throw new CommandLaunchException("Command exited with status " + process.exitValue() + ".");
            }
        } catch (IOException ex) {
            throw new CommandLaunchException("Unable to start command.", ex);
        } catch (InterruptedException ex) {
            boolean stopped = process == null || stop(process);
            Thread.currentThread().interrupt();
            throw new CommandLaunchException(
                    stopped
                            ? "Interrupted while starting command."
                            : "Interrupted while starting command; the command could not be stopped.",
                    ex);
        }
    }

    private static boolean stop(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (awaitStopped(process, descendants, 100)) {

            return true;
        }
        descendants.forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        if (process.isAlive()) {
            process.destroyForcibly();
        }

        return awaitStopped(process, descendants, 1_000);
    }

    private static boolean awaitStopped(Process process, List<ProcessHandle> descendants, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean interrupted = false;
        try {
            while (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {

                    return false;
                }
                long waitMillis = Math.max(1L, Math.min(25L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                try {
                    if (process.isAlive()) {
                        process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
                    } else {
                        Thread.sleep(waitMillis);
                    }
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }

            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
