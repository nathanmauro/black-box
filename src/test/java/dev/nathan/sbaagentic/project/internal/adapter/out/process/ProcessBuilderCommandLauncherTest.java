package dev.nathan.sbaagentic.project.internal.adapter.out.process;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import dev.nathan.sbaagentic.project.internal.application.port.CommandLaunchException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ProcessBuilderCommandLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void passesShellLookingValuesAsDiscreteArgvWithoutEvaluation() throws Exception {
        Path log = tempDir.resolve("argv.bin");
        Path sentinel = tempDir.resolve("should-not-exist");
        Path script = executable("capture.sh", """
                #!/bin/sh
                printf '%%s\\0' "$@" > '%s'
                """.formatted(log));
        String shellLooking = "safe; touch " + sentinel;
        String substitution = "$(touch " + sentinel + ")";

        new ProcessBuilderCommandLauncher().launch(
                List.of(script.toString(), "space name", shellLooking, substitution, "line\nbreak"),
                Duration.ofSeconds(2));

        List<String> argv = Arrays.stream(Files.readString(log, StandardCharsets.UTF_8).split("\\x00", -1))
                .filter(value -> !value.isEmpty())
                .toList();
        assertThat(argv).containsExactly("space name", shellLooking, substitution, "line\nbreak");
        assertThat(sentinel).doesNotExist();
    }

    @Test
    void reportsImmediateNonZeroExit() throws Exception {
        Path script = executable("fail.sh", "#!/bin/sh\nexit 7\n");

        assertThatThrownBy(() -> new ProcessBuilderCommandLauncher()
                .launch(List.of(script.toString()), Duration.ofSeconds(2)))
                .isInstanceOf(CommandLaunchException.class)
                .hasMessageContaining("7");
    }

    @Test
    void reportsAndStopsCommandsThatDoNotHandOffBeforeTheTimeout() throws Exception {
        Path pids = tempDir.resolve("timeout-pids");
        Path script = hangingScript("timeout.sh", pids);

        assertThatThrownBy(() -> new ProcessBuilderCommandLauncher()
                .launch(List.of(script.toString()), Duration.ofMillis(200)))
                .isInstanceOf(CommandLaunchException.class)
                .hasMessageContaining("timed out");

        assertStopped(pids);
    }

    @Test
    void interruptionStopsTheCommandAndPreservesTheInterrupt() throws Exception {
        Path pids = tempDir.resolve("interrupted-pids");
        Path script = hangingScript("interrupted.sh", pids);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread launcherThread = new Thread(() -> {
            try {
                new ProcessBuilderCommandLauncher()
                        .launch(List.of(script.toString()), Duration.ofSeconds(30));
            }
            catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        launcherThread.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> Files.exists(pids));
        launcherThread.interrupt();
        await().atMost(Duration.ofSeconds(2)).until(() -> !launcherThread.isAlive());

        assertThat(failure.get())
                .isInstanceOf(CommandLaunchException.class)
                .hasMessageContaining("Interrupted");
        assertThat(launcherThread.isInterrupted()).isTrue();
        assertStopped(pids);
    }

    private Path executable(String name, String body) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private Path hangingScript(String name, Path pids) throws Exception {
        return executable(name, """
                #!/bin/sh
                sleep 30 &
                child=$!
                printf '%%s %%s' "$$" "$child" > '%s'
                wait "$child"
                """.formatted(pids));
    }

    private static void assertStopped(Path pids) throws Exception {
        for (String value : Files.readString(pids).trim().split("\\s+")) {
            long pid = Long.parseLong(value);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                    .as("process %s must be stopped", pid)
                    .isFalse();
        }
    }
}
