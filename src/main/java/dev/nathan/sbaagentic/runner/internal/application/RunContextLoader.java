package dev.nathan.sbaagentic.runner.internal.application;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatter;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSpec;

public class RunContextLoader {

    private final BlackBoxApiClient apiClient;
    private final StoryFrontmatterParser frontmatterParser;

    public RunContextLoader(BlackBoxApiClient apiClient, StoryFrontmatterParser frontmatterParser) {
        this.apiClient = apiClient;
        this.frontmatterParser = frontmatterParser;
    }

    public Optional<StageContext> loadSdlcContext(
            TaskChange claimedTask,
            RunnerConfig config,
            String actorId,
            String stageLabel) {
        Task task = claimedTask.snapshot().task();
        TaskSpec spec = claimedTask.snapshot().spec();
        if (spec == null) {
            spec = apiClient.getSpec(task.specId());
        }
        Optional<StoryFrontmatterParser.ParsedStory> parsed = frontmatterParser.parse(spec.body());
        if (parsed.isEmpty()
                || !"sdlc".equals(parsed.orElseThrow().frontmatter().mode())
                || isBlank(parsed.orElseThrow().frontmatter().repo())) {
            block(task.id(), actorId, stageLabel + " task has invalid SDLC story frontmatter.");
            return Optional.empty();
        }
        StoryFrontmatter frontmatter = parsed.orElseThrow().frontmatter();
        Optional<RepoConfig> matchingRepo = safeList(config.repos()).stream()
                .filter(Objects::nonNull)
                .filter(repo -> Objects.equals(repo.path(), frontmatter.repo()))
                .findFirst();
        if (matchingRepo.isEmpty()) {
            block(task.id(), actorId,
                    stageLabel + " repo is no longer present in runner config: " + frontmatter.repo());
            return Optional.empty();
        }
        RepoConfig repoConfig = matchingRepo.orElseThrow();
        File repoDir = new File(repoConfig.path()).getAbsoluteFile();
        String resolvedVerify = resolveVerify(frontmatter.verify(), repoConfig, repoDir);
        if (isBlank(resolvedVerify)) {
            block(task.id(), actorId, stageLabel + " task has no resolvable verify command.");
            return Optional.empty();
        }
        return Optional.of(new StageContext(spec, repoConfig, repoDir, resolvedVerify));
    }

    public static String resolveVerify(String storyVerify, RepoConfig repoConfig, File repoDir) {
        if (!isBlank(storyVerify)) {
            return storyVerify;
        }
        if (!isBlank(repoConfig.verify())) {
            return repoConfig.verify();
        }
        if (new File(repoDir, "pom.xml").isFile()) {
            return "mvn test";
        }
        if (new File(repoDir, "package.json").isFile()) {
            return "npm test";
        }
        if (new File(repoDir, "Makefile").isFile()) {
            return "make test";
        }
        return null;
    }

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record StageContext(
            TaskSpec spec,
            RepoConfig repoConfig,
            File repoDir,
            String resolvedVerify) {
    }
}
