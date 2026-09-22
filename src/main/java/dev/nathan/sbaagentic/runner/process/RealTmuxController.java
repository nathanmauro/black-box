package dev.nathan.sbaagentic.runner.process;

import java.io.File;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RealTmuxController implements TmuxController {

    private static final Duration TMUX_TIMEOUT = Duration.ofSeconds(10);

    private final ProcessRunner processRunner;

    public RealTmuxController(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    @Override
    public boolean hasSession(String sessionName) {
        ProcessRunner.ProcessResult result = run(List.of("tmux", "has-session", "-t", sessionName), null);
        if (result.timedOut()) {
            throw failure("inspect session " + sessionName, result);
        }
        if (result.exitCode() == 0) {

            return true;
        }
        String detail = result.stderr() == null ? "" : result.stderr().strip();
        if (result.exitCode() == 1
                && (detail.startsWith("can't find session:")
                        || detail.startsWith("no server running on ")
                        || (detail.startsWith("error connecting to ")
                                && detail.endsWith("(No such file or directory)")))) {

            return false;
        }
        throw failure("inspect session " + sessionName, result);
    }

    @Override
    public void killSession(String sessionName) {
        if (!hasSession(sessionName)) {

            return;
        }
        ProcessRunner.ProcessResult result = run(List.of("tmux", "kill-session", "-t", sessionName), null);
        if (result.exitCode() != 0 && hasSession(sessionName)) {
            throw failure("kill session " + sessionName, result);
        }
    }

    @Override
    public void newSession(String sessionName, File cwd, int width, int height) {
        ProcessRunner.ProcessResult result = run(
                List.of(
                        "tmux",
                        "new-session",
                        "-d",
                        "-x",
                        Integer.toString(width),
                        "-y",
                        Integer.toString(height),
                        "-c",
                        cwd.getAbsolutePath(),
                        "-s",
                        sessionName),
                cwd);
        requireSuccess("create session " + sessionName, result);
    }

    @Override
    public void sendKeys(String sessionName, String text) {
        // Each value is a separate argv element, so no shell reparses the text and quoting would double-escape it.
        ProcessRunner.ProcessResult result = run(List.of("tmux", "send-keys", "-t", sessionName, text, "Enter"), null);
        requireSuccess("send keys to " + sessionName, result);
    }

    @Override
    public String capturePane(String sessionName) {
        ProcessRunner.ProcessResult result = run(List.of("tmux", "capture-pane", "-p", "-t", sessionName), null);
        requireSuccess("capture pane for " + sessionName, result);

        return result.stdout();
    }

    private ProcessRunner.ProcessResult run(List<String> command, File workingDir) {

        return processRunner.run(command, workingDir, TMUX_TIMEOUT);
    }

    private static void requireSuccess(String action, ProcessRunner.ProcessResult result) {
        if (result.exitCode() != 0 || result.timedOut()) {
            throw failure(action, result);
        }
    }

    private static IllegalStateException failure(String action, ProcessRunner.ProcessResult result) {
        String detail = result.stderr() == null || result.stderr().isBlank() ? result.stdout() : result.stderr();

        return new IllegalStateException("Unable to " + action + " (exit " + result.exitCode()
                + (result.timedOut() ? ", timed out" : "") + "): " + detail);
    }
}
