package dev.nathan.sbaagentic.runner.process;

import java.io.File;
import java.time.Duration;
import java.util.List;

public interface ProcessRunner {

    ProcessResult run(List<String> command, File workingDir, Duration timeout);

    record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    }
}
