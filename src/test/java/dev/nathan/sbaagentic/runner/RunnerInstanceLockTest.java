package dev.nathan.sbaagentic.runner;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerInstanceLockTest {

    @TempDir
    Path tempDir;

    @Test
    void lockIsExclusiveAndCanBeReacquiredAfterRelease() throws Exception {
        Path lockFile = tempDir.resolve("nested/runner.lock");

        Optional<RunnerInstanceLock> first = RunnerInstanceLock.tryAcquire(lockFile);
        assertThat(first).isPresent();
        try (RunnerInstanceLock ignored = first.orElseThrow()) {
            Optional<RunnerInstanceLock> second = RunnerInstanceLock.tryAcquire(lockFile);
            assertThat(second).isEmpty();
        }

        Optional<RunnerInstanceLock> third = RunnerInstanceLock.tryAcquire(lockFile);
        assertThat(third).isPresent();
        try (RunnerInstanceLock ignored = third.orElseThrow()) {
            assertThat(lockFile).exists();
        }
    }
}
