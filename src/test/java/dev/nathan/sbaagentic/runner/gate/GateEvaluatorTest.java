package dev.nathan.sbaagentic.runner.gate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.RealProcessRunner;
import dev.nathan.sbaagentic.task.TaskSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GateEvaluatorTest {

    @TempDir
    Path tempDir;

    private final RealProcessRunner processRunner = new RealProcessRunner();

    @Test
    void passesWithExplicitVerifyCommand() throws Exception {
        Path repo = gitRepo("explicit-verify");

        GateResult result = evaluate(
                story(repo, "mvn -q test", false, acceptanceCriteria()),
                config(repoConfig(repo, true, "")));

        assertThat(result.pass()).isTrue();
        assertThat(result.findings()).isEmpty();
        assertThat(result.resolvedVerify()).isEqualTo("mvn -q test");
    }

    @Test
    void passesWithVerifyDerivedFromPom() throws Exception {
        Path repo = gitRepo("derived-verify");
        Files.writeString(repo.resolve("pom.xml"), "<project/>");

        GateResult result = evaluate(
                story(repo, null, false, acceptanceCriteria()),
                config(repoConfig(repo, true, "")));

        assertThat(result.pass()).isTrue();
        assertThat(result.resolvedVerify()).isEqualTo("mvn test");
    }

    @Test
    void failsWhenRepoPathDoesNotExist() {
        Path repo = tempDir.resolve("missing");

        GateResult result = evaluate(
                story(repo, "mvn test", false, acceptanceCriteria()),
                config(repoConfig(repo, true, "")));

        assertThat(result.pass()).isFalse();
        assertThat(result.findings()).contains("repo path does not exist: " + repo);
    }

    @Test
    void failsWhenRepoPathIsNotGitWorkingTree() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("plain-directory"));

        GateResult result = evaluate(
                story(repo, "mvn test", false, acceptanceCriteria()),
                config(repoConfig(repo, true, "")));

        assertThat(result.pass()).isFalse();
        assertThat(result.findings()).contains("repo path is not a git working tree: " + repo);
    }

    @Test
    void failsWhenRepoIsNotAllowlisted() throws Exception {
        Path repo = gitRepo("not-allowlisted");

        GateResult result = evaluate(
                story(repo, "mvn test", false, acceptanceCriteria()),
                config());

        assertThat(result.pass()).isFalse();
        assertThat(result.findings()).contains(
                "repo is not in the runner config allowlist: " + repo + " (configured repos: )",
                "push intent check was not run because repo is not in the runner config allowlist");
    }

    @Test
    void failsWhenAcceptanceCriteriaIsMissingOrEmpty() throws Exception {
        Path repo = gitRepo("acceptance");
        RunnerConfig config = config(repoConfig(repo, true, ""));

        GateResult missing = evaluate(
                story(repo, "mvn test", false, "## Goal\nBuild it.\n"), config);
        GateResult empty = evaluate(
                story(repo, "mvn test", false, "## Acceptance criteria\n\n## Constraints\nNone.\n"),
                config);

        assertThat(missing.pass()).isFalse();
        assertThat(missing.findings()).contains("Acceptance criteria section is missing");
        assertThat(empty.pass()).isFalse();
        assertThat(empty.findings()).contains("Acceptance criteria section is present but empty");
    }

    @Test
    void failsWhenVerifyCannotBeDerived() throws Exception {
        Path repo = gitRepo("no-verify-convention");

        GateResult result = evaluate(
                story(repo, null, false, acceptanceCriteria()),
                config(repoConfig(repo, true, "")));

        assertThat(result.pass()).isFalse();
        assertThat(result.resolvedVerify()).isNull();
        assertThat(result.findings()).contains(
                "verify command not specified and not derivable "
                        + "(no pom.xml/package.json/Makefile at repo root)");
    }

    @Test
    void failsWhenPushIntentConflictsWithDangerFlag() throws Exception {
        Path repo = gitRepo("danger-veto");

        GateResult result = evaluate(
                story(repo, "mvn test", true, acceptanceCriteria()),
                config(repoConfig(repo, true, "production repo")));

        assertThat(result.pass()).isFalse();
        assertThat(result.findings()).contains(
                "story requests push:true but repo config carries a danger flag: production repo");
    }

    @Test
    void failsWhenPushIntentConflictsWithRepoPushVeto() throws Exception {
        Path repo = gitRepo("push-veto");

        GateResult result = evaluate(
                story(repo, "mvn test", true, acceptanceCriteria()),
                config(repoConfig(repo, false, "")));

        assertThat(result.pass()).isFalse();
        assertThat(result.findings()).contains(
                "story requests push:true but repo config has push:false");
    }

    @Test
    void multipleSimultaneousFailuresProduceAllRelevantFindings() {
        GateResult result = evaluate("# Story without frontmatter", config());

        assertThat(result.pass()).isFalse();
        assertThat(result.findings()).contains(
                "Story frontmatter missing or malformed (expected YAML between --- markers).",
                "repo field is missing",
                "repo is not in the runner config allowlist: null (configured repos: )",
                "Acceptance criteria section is missing",
                "verify command not specified and not derivable "
                        + "(no pom.xml/package.json/Makefile at repo root)",
                "push intent check was not run because repo is not in the runner config allowlist");
    }

    @Test
    void advisorFeedbackNeverFlipsDeterministicPass() throws Exception {
        Path repo = gitRepo("advisor");
        GateAdvisor blockingAdvisor = (storyBody, findings) ->
                new GateAdvisor.GateAdvisorNote("Review the story wording.", true);

        GateResult result = evaluator(blockingAdvisor).evaluate(
                taskSpec(story(repo, "mvn test", false, acceptanceCriteria())),
                config(repoConfig(repo, true, "")));

        assertThat(result.pass()).isTrue();
        assertThat(result.findings()).containsExactly("[advisor] Review the story wording.");
        assertThat(result.advisorNote().blocking()).isTrue();
    }

    private GateResult evaluate(String body, RunnerConfig config) {
        return evaluator(noOpAdvisor()).evaluate(taskSpec(body), config);
    }

    private GateEvaluator evaluator(GateAdvisor advisor) {
        return new GateEvaluator(new StoryFrontmatterParser(), processRunner, advisor);
    }

    private Path gitRepo(String name) throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve(name));
        ProcessResult result = processRunner.run(
                List.of("git", "init"), repo.toFile(), Duration.ofSeconds(10));
        assertThat(result.exitCode())
                .as("git init stderr: %s", result.stderr())
                .isZero();
        return repo;
    }

    private static GateAdvisor noOpAdvisor() {
        return (storyBody, findings) -> new GateAdvisor.GateAdvisorNote("", false);
    }

    private static RunnerConfig config(RepoConfig... repos) {
        return new RunnerConfig(1, List.of(), null, List.of(repos));
    }

    private static RepoConfig repoConfig(Path repo, boolean push, String danger) {
        return new RepoConfig(repo.toString(), push, false, null, danger);
    }

    private static TaskSpec taskSpec(String body) {
        return new TaskSpec(
                "spec-1", "/tmp/project", "Story", body, null, null, "test", null, null);
    }

    private static String story(
            Path repo,
            String verify,
            boolean push,
            String bodyMarkdown) {
        String verifyLine = verify == null ? "" : "verify: '" + verify.replace("'", "''") + "'\n";
        return "---\n"
                + "story: v1\n"
                + "repo: '" + repo.toString().replace("'", "''") + "'\n"
                + "mode: full_auto\n"
                + verifyLine
                + "push: " + push + "\n"
                + "priority: 10\n"
                + "---\n"
                + "# Story\n\n"
                + bodyMarkdown;
    }

    private static String acceptanceCriteria() {
        return "## Acceptance criteria\n- The requested behavior works.\n";
    }
}
