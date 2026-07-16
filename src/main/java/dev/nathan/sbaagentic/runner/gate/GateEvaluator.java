package dev.nathan.sbaagentic.runner.gate;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser.ParsedStory;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.task.TaskSpec;

import org.springframework.stereotype.Component;

@Component
public class GateEvaluator {

    private static final Duration GIT_PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern ACCEPTANCE_HEADING = Pattern.compile(
            "(?im)^[ \\t]*#{1,6}[ \\t]+acceptance criteria[ \\t]*#*[ \\t]*\\r?$");
    private static final Pattern ANY_HEADING = Pattern.compile(
            "(?m)^[ \\t]*#{1,6}[ \\t]+.*\\r?$");

    private final StoryFrontmatterParser frontmatterParser;
    private final ProcessRunner processRunner;
    private final GateAdvisor gateAdvisor;

    public GateEvaluator(
            StoryFrontmatterParser frontmatterParser,
            ProcessRunner processRunner,
            GateAdvisor gateAdvisor) {
        this.frontmatterParser = frontmatterParser;
        this.processRunner = processRunner;
        this.gateAdvisor = gateAdvisor;
    }

    public GateResult evaluate(TaskSpec spec, RunnerConfig config) {
        List<String> findings = new ArrayList<>();
        Optional<ParsedStory> parsedStory = frontmatterParser.parse(spec.body());
        boolean frontmatterPassed = parsedStory.isPresent();
        if (!frontmatterPassed) {
            findings.add("Story frontmatter missing or malformed (expected YAML between --- markers).");
        }

        StoryFrontmatter frontmatter = parsedStory
                .map(ParsedStory::frontmatter)
                .orElseGet(() -> new StoryFrontmatter(null, null, null, null, null, null));
        String bodyMarkdown = parsedStory.map(ParsedStory::bodyMarkdown).orElse("");
        String repoPath = frontmatter.repo();

        boolean repoPassed = checkRepo(repoPath, findings);

        List<RepoConfig> configuredRepos = config.repos() == null ? List.of() : config.repos();
        Optional<RepoConfig> matchingRepo = repoPath == null || repoPath.isBlank()
                ? Optional.empty()
                : configuredRepos.stream()
                        .filter(repo -> repo != null && Objects.equals(repo.path(), repoPath))
                        .findFirst();
        boolean allowlistPassed = matchingRepo.isPresent();
        if (!allowlistPassed) {
            String configuredPaths = configuredRepos.stream()
                    .filter(Objects::nonNull)
                    .map(RepoConfig::path)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            findings.add("repo is not in the runner config allowlist: " + repoPath
                    + " (configured repos: " + configuredPaths + ")");
        }

        boolean acceptancePassed = checkAcceptanceCriteria(bodyMarkdown, findings);

        String resolvedVerify = resolveVerify(frontmatter.verify(), repoPath);
        boolean verifyPassed = resolvedVerify != null;
        if (!verifyPassed) {
            findings.add("verify command not specified and not derivable "
                    + "(no pom.xml/package.json/Makefile at repo root)");
        }

        boolean pushPassed = checkPushIntent(frontmatter.push(), matchingRepo, findings);

        boolean deterministicPass = frontmatterPassed
                && repoPassed
                && allowlistPassed
                && acceptancePassed
                && verifyPassed
                && pushPassed;

        GateAdvisor.GateAdvisorNote advisorNote = gateAdvisor.advise(spec.body(), List.copyOf(findings));
        if (advisorNote != null
                && advisorNote.feedback() != null
                && !advisorNote.feedback().isBlank()) {
            findings.add("[advisor] " + advisorNote.feedback());
        }
        // Advisory output is intentionally excluded from pass/fail in gate v1.
        return new GateResult(deterministicPass, findings, resolvedVerify, advisorNote);
    }

    private boolean checkRepo(String repoPath, List<String> findings) {
        if (repoPath == null || repoPath.isBlank()) {
            findings.add("repo field is missing");
            return false;
        }

        File repoDirectory = new File(repoPath);
        if (!repoDirectory.isDirectory()) {
            findings.add("repo path does not exist: " + repoPath);
            return false;
        }

        ProcessResult result = processRunner.run(
                List.of("git", "-C", repoPath, "rev-parse", "--is-inside-work-tree"),
                repoDirectory,
                GIT_PROBE_TIMEOUT);
        if (result.exitCode() != 0 || !"true".equals(result.stdout().strip())) {
            findings.add("repo path is not a git working tree: " + repoPath);
            return false;
        }
        return true;
    }

    private static boolean checkAcceptanceCriteria(String bodyMarkdown, List<String> findings) {
        Matcher acceptanceHeading = ACCEPTANCE_HEADING.matcher(bodyMarkdown);
        if (!acceptanceHeading.find()) {
            findings.add("Acceptance criteria section is missing");
            return false;
        }

        Matcher nextHeading = ANY_HEADING.matcher(bodyMarkdown);
        int sectionEnd = bodyMarkdown.length();
        if (nextHeading.find(acceptanceHeading.end())) {
            sectionEnd = nextHeading.start();
        }
        String section = bodyMarkdown.substring(acceptanceHeading.end(), sectionEnd);
        boolean hasContent = section.lines()
                .anyMatch(line -> !line.isBlank() && !ANY_HEADING.matcher(line).matches());
        if (!hasContent) {
            findings.add("Acceptance criteria section is present but empty");
        }
        return hasContent;
    }

    private static String resolveVerify(String configuredVerify, String repoPath) {
        if (configuredVerify != null && !configuredVerify.isBlank()) {
            return configuredVerify;
        }
        if (repoPath == null || repoPath.isBlank()) {
            return null;
        }

        File repoDirectory = new File(repoPath);
        if (new File(repoDirectory, "pom.xml").isFile()) {
            return "mvn test";
        }
        if (new File(repoDirectory, "package.json").isFile()) {
            return "npm test";
        }
        if (new File(repoDirectory, "Makefile").isFile()) {
            return "make test";
        }
        return null;
    }

    private static boolean checkPushIntent(
            Boolean pushRequested,
            Optional<RepoConfig> matchingRepo,
            List<String> findings) {
        if (matchingRepo.isEmpty()) {
            findings.add("push intent check was not run because repo is not in the runner config allowlist");
            return false;
        }
        if (pushRequested != Boolean.TRUE) {
            return true;
        }

        RepoConfig repoConfig = matchingRepo.orElseThrow();
        boolean passed = true;
        if (repoConfig.danger() != null && !repoConfig.danger().isBlank()) {
            findings.add("story requests push:true but repo config carries a danger flag: "
                    + repoConfig.danger());
            passed = false;
        }
        if (!repoConfig.push()) {
            findings.add("story requests push:true but repo config has push:false");
            passed = false;
        }
        return passed;
    }
}
