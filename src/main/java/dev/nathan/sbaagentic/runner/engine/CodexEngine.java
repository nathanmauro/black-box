package dev.nathan.sbaagentic.runner.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.EngineConfig;

import org.springframework.stereotype.Component;

@Component
public class CodexEngine implements Engine {

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public List<String> command(String prompt, EngineConfig config, File worktreeDir) {
        List<String> command = new ArrayList<>(List.of(
                "codex",
                "-m",
                config.model(),
                "-c",
                "model_reasoning_effort=\"" + config.effort() + "\"",
                "-a",
                "never"));
        if (config.sandbox() != null && !config.sandbox().isBlank()) {
            command.add("--sandbox");
            command.add(config.sandbox());
            if ("workspace-write".equals(config.sandbox())) {
                // The sandbox denies all network by default, including loopback — but the
                // completion protocol requires workers to POST report.sh annotations to the
                // local Black Box API. Without this, plan/review stages can never report.
                command.add("-c");
                command.add("sandbox_workspace_write.network_access=true");
                // A linked worktree keeps its git metadata under the parent repository's
                // .git directory, outside the sandbox's writable cwd — without this root,
                // git add/commit cannot create index.lock and the build stage fail-closes.
                gitCommonDir(worktreeDir).ifPresent(common -> {
                    command.add("-c");
                    command.add("sandbox_workspace_write.writable_roots=[\"" + common + "\"]");
                });
            }
        }
        // Codex's TUI stalls unattended workers on a one-time "do you trust this
        // directory?" prompt for any repo root it has never seen. Its -c parser splits
        // keys on every dot with no quoted-segment support, so a
        // projects."<path>".trust_level key can never address a filesystem path — the
        // whole table has to ride in the TOML-parsed value under the single-segment
        // "projects" key instead.
        if (worktreeDir != null) {
            command.add("-c");
            command.add("projects={" + trustedProjectEntries(worktreeDir) + "}");
        }
        // End-of-options marker: goal prompts can open with story frontmatter ("---"),
        // which codex's argument parser would otherwise reject as a malformed flag.
        command.add("--");
        command.add(prompt);
        return List.copyOf(command);
    }

    // Codex looks projects up by the process's physical cwd (symlinks resolved), so the
    // absolute path alone misses when the worktree sits behind a symlink such as
    // /tmp -> /private/tmp. Emit both forms whenever they differ.
    private static String trustedProjectEntries(File worktreeDir) {
        String absolute = worktreeDir.getAbsolutePath();
        String canonical;
        try {
            canonical = worktreeDir.getCanonicalPath();
        }
        catch (IOException ex) {
            canonical = absolute;
        }
        StringBuilder entries = new StringBuilder(trustedProjectEntry(absolute));
        if (!canonical.equals(absolute)) {
            entries.append(',').append(trustedProjectEntry(canonical));
        }
        return entries.toString();
    }

    private static String trustedProjectEntry(String path) {
        return "\"" + tomlEscape(path) + "\"={trust_level=\"trusted\"}";
    }

    private static String tomlEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // A linked worktree's .git is a pointer file: "gitdir: <repo>/.git/worktrees/<name>".
    // The common directory two levels up is where index locks, refs, and objects live.
    private static Optional<String> gitCommonDir(File worktreeDir) {
        if (worktreeDir == null) {
            return Optional.empty();
        }
        Path pointer = worktreeDir.toPath().resolve(".git");
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(pointer).strip();
            if (!content.startsWith("gitdir:")) {
                return Optional.empty();
            }
            Path gitDir = Path.of(content.substring("gitdir:".length()).strip());
            Path common = gitDir.getParent() == null ? null : gitDir.getParent().getParent();
            return common == null ? Optional.empty() : Optional.of(common.toAbsolutePath().toString());
        }
        catch (IOException ex) {
            return Optional.empty();
        }
    }
}
