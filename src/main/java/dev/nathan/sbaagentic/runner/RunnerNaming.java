package dev.nathan.sbaagentic.runner;

import java.nio.file.Path;

public final class RunnerNaming {

    private RunnerNaming() {
    }

    /**
     * Resolves a {@code scripts/runner/*.sh} helper to an absolute path anchored at the runner
     * JVM's own working directory (the sba-agentic checkout it was launched from), never at the
     * target repo/worktree being operated on. The runner drives arbitrary other repos per its
     * {@code repos} config — those repos do not carry a copy of these scripts, so a bare relative
     * path only resolves by accident when the target repo happens to be sba-agentic itself.
     */
    public static String scriptPath(String relativeScriptPath) {
        return Path.of(System.getProperty("user.dir"), relativeScriptPath)
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    public static String taskShort(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task id is required");
        }
        String normalized = taskId.strip();
        return normalized.substring(0, Math.min(8, normalized.length()));
    }

    public static String tmuxSessionName(String taskId) {
        return "bb-run-" + taskShort(taskId);
    }

    public static String worktreeDirName(String taskId) {
        return ".worktrees/bb-" + taskShort(taskId);
    }
}
