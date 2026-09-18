package dev.nathan.sbaagentic.project.internal.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import dev.nathan.sbaagentic.project.CodeNavigationResult;
import dev.nathan.sbaagentic.project.CodeProjectScope;
import dev.nathan.sbaagentic.project.CodeReference;
import dev.nathan.sbaagentic.project.EditorProperties;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.project.ProjectScope;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.internal.application.port.CommandLauncher;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectCatalogStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeNavigationServiceTest {

    @TempDir
    Path tempDir;

    private ProjectCatalogStore catalog;
    private CapturingLauncher launcher;
    private EditorProperties properties;

    @BeforeEach
    void setUp() {
        catalog = mock(ProjectCatalogStore.class);
        launcher = new CapturingLauncher();
        properties = new EditorProperties();
        properties.setEnabled(true);
        properties.setCommand("/bin/echo");
        properties.setAllowlist(List.of("/bin/echo"));
    }

    @Test
    void exposesOnlyVerifiedCatalogScopesAndOpensAgainstTheContainingWorkspace() throws Exception {
        Path repo = gitRepo("repo");
        Path nested = Files.createDirectories(repo.resolve("frontend"));
        Path target = nested.resolve("src/app.ts");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "one\ntwo\nthree\n");

        String nestedKey = ProjectKey.of(nested.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(
                repo,
                new ProjectScope(ProjectKey.of(repo.toString()).encoded(), repo.toString(), "repo", true, null),
                new ProjectScope(nestedKey, nested.toString(), "frontend", false, "manual"))));

        CodeNavigationService service = service();

        assertThat(service.codeScopes())
                .extracting(CodeProjectScope::projectKey)
                .contains(ProjectKey.of(repo.toString()).encoded(), nestedKey);
        assertThat(service.openInEditor(new CodeReference(nestedKey, "src/app.ts", 2, 2, "abc")))
                .isEqualTo(new CodeNavigationResult("opened"));
        assertThat(launcher.commands).hasSize(2);
        assertThat(launcher.commands.get(0))
                .containsExactly(Path.of("/bin/echo").toRealPath().toString(), repo.toRealPath().toString());
        assertThat(launcher.commands.get(1))
                .containsExactly(
                        Path.of("/bin/echo").toRealPath().toString(),
                        "-g",
                        target.toRealPath() + ":2:2");
    }

    @Test
    void preservesAnExactWorktreeScopeInsteadOfAliasCollapsingToPrimary() throws Exception {
        Path primary = gitRepo("primary");
        Path worktree = gitWorktree(primary, "worktree");
        Path target = worktree.resolve("src/Work.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "class Work {}\n");
        String worktreeKey = ProjectKey.of(worktree.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(
                primary,
                new ProjectScope(ProjectKey.of(primary.toString()).encoded(), primary.toString(), "primary", true, null),
                new ProjectScope(worktreeKey, worktree.toString(), "worktree", false, "git-commondir"))));

        service().openInEditor(new CodeReference(worktreeKey, "src/Work.java", 1, null, null));

        assertThat(launcher.commands.get(0).get(1)).isEqualTo(worktree.toRealPath().toString());
        assertThat(launcher.commands.get(1).get(2)).startsWith(target.toRealPath().toString());
    }

    @Test
    void rejectsBroadUnknownStaleAndNonGitScopes() throws Exception {
        Path nonGit = Files.createDirectories(tempDir.resolve("plain"));
        Path stale = tempDir.resolve("gone");
        Path meldOnly = gitRepo("meld-only");
        when(catalog.summaries()).thenReturn(List.of(
                summary(Path.of("/"), scope("/")),
                summary(Path.of(System.getProperty("user.home")), scope(System.getProperty("user.home"))),
                summary(Path.of(ProjectKey.NO_PROJECT), scope(ProjectKey.NO_PROJECT)),
                summary(nonGit, scope(nonGit.toString())),
                summary(stale, scope(stale.toString())),
                summaryWithSessionCount(meldOnly, 0, scope(meldOnly.toString()))));

        CodeNavigationService service = service();

        assertThat(service.codeScopes()).isEmpty();
        assertCode(service, new CodeReference(ProjectKey.of("/").encoded(), "etc/passwd", null, null, null),
                CodeNavigationError.PROJECT_UNRESOLVED);
        assertCode(service, new CodeReference(ProjectKey.of(nonGit.toString()).encoded(), "note.txt", null, null, null),
                CodeNavigationError.PROJECT_UNRESOLVED);
        assertCode(service, new CodeReference("unknown", "note.txt", null, null, null),
                CodeNavigationError.PROJECT_UNRESOLVED);
    }

    @Test
    void rejectsTraversalAbsoluteTargetsSymlinkEscapeAndMissingFiles() throws Exception {
        Path repo = gitRepo("secure");
        Path outside = tempDir.resolve("secret.txt");
        Files.writeString(outside, "secret\n");
        Files.createSymbolicLink(repo.resolve("escape.txt"), outside);
        String key = ProjectKey.of(repo.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(repo, scope(repo.toString()))));
        CodeNavigationService service = service();

        assertCode(service, new CodeReference(key, "../secret.txt", null, null, null),
                CodeNavigationError.OUTSIDE_PROJECT_ROOT);
        assertCode(service, new CodeReference(key, outside.toString(), null, null, null),
                CodeNavigationError.OUTSIDE_PROJECT_ROOT);
        assertCode(service, new CodeReference(key, "escape.txt", null, null, null),
                CodeNavigationError.OUTSIDE_PROJECT_ROOT);
        assertCode(service, new CodeReference(key, "missing.txt", null, null, null),
                CodeNavigationError.FILE_MISSING);
        assertCode(service, new CodeReference(key, ".", null, null, null),
                CodeNavigationError.FILE_MISSING);
        assertThat(launcher.commands).isEmpty();
    }

    @Test
    void rejectsSymlinksThatLeaveAnExactNestedScopeEvenWithinTheSameRepository() throws Exception {
        Path repo = gitRepo("nested-secure");
        Path nested = Files.createDirectories(repo.resolve("frontend"));
        Path sibling = Files.createDirectories(repo.resolve("private")).resolve("secret.txt");
        Files.writeString(sibling, "secret\n");
        Files.createSymbolicLink(nested.resolve("escape.txt"), sibling);
        String nestedKey = ProjectKey.of(nested.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(
                repo,
                new ProjectScope(nestedKey, nested.toString(), "frontend", true, null))));

        assertCode(service(), new CodeReference(nestedKey, "escape.txt", null, null, null),
                CodeNavigationError.OUTSIDE_PROJECT_ROOT);
        assertThat(launcher.commands).isEmpty();
    }

    @Test
    void validatesOneBasedLineAndColumnBounds() throws Exception {
        Path repo = gitRepo("lines");
        Files.writeString(repo.resolve("file.txt"), "abc\nz\n");
        Files.writeString(repo.resolve("empty.txt"), "");
        String key = ProjectKey.of(repo.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(repo, scope(repo.toString()))));
        CodeNavigationService service = service();

        assertCode(service, new CodeReference(key, "file.txt", 0, null, null),
                CodeNavigationError.INVALID_REFERENCE);
        assertThat(service.openInEditor(new CodeReference(key, "file.txt", 3, 1, null)))
                .isEqualTo(new CodeNavigationResult("opened"));
        launcher.commands.clear();
        assertThat(service.openInEditor(new CodeReference(key, "empty.txt", 1, 1, null)))
                .isEqualTo(new CodeNavigationResult("opened"));
        launcher.commands.clear();
        assertCode(service, new CodeReference(key, "file.txt", 4, null, null),
                CodeNavigationError.INVALID_REFERENCE);
        assertCode(service, new CodeReference(key, "file.txt", null, 1, null),
                CodeNavigationError.INVALID_REFERENCE);
        assertCode(service, new CodeReference(key, "file.txt", 1, 5, null),
                CodeNavigationError.INVALID_REFERENCE);
    }

    @Test
    void rejectsDisabledAndNonAllowlistedEditorCommandsBeforeLaunching() throws Exception {
        Path repo = gitRepo("disabled");
        Files.writeString(repo.resolve("file.txt"), "ok\n");
        String key = ProjectKey.of(repo.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(repo, scope(repo.toString()))));

        properties.setEnabled(false);
        assertCode(service(), new CodeReference(key, "file.txt", 1, null, null),
                CodeNavigationError.EDITOR_DISABLED);

        properties.setEnabled(true);
        properties.setAllowlist(List.of("/usr/bin/false"));
        assertCode(service(), new CodeReference(key, "file.txt", 1, null, null),
                CodeNavigationError.EDITOR_DISABLED);
        assertThat(launcher.commands).isEmpty();
    }

    @Test
    void revealsOnlyAfterTheSameSecureResolution() throws Exception {
        assumeTrue(finderCommandPresent(),
                "Reveal needs the platform Finder command; it is absent on this machine.");
        Path repo = gitRepo("reveal");
        Path target = repo.resolve("file.txt");
        Files.writeString(target, "ok\n");
        String key = ProjectKey.of(repo.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(repo, scope(repo.toString()))));

        assertThat(service().revealInFinder(new CodeReference(key, "file.txt", null, null, null)))
                .isEqualTo(new CodeNavigationResult("revealed"));
        assertThat(launcher.commands).singleElement()
                .satisfies(command -> assertThat(command)
                        .containsExactly("/usr/bin/open", "-R", target.toRealPath().toString()));
    }

    @Test
    void revealFailsClosedWhereTheFinderCommandIsAbsent() throws Exception {
        assumeFalse(finderCommandPresent(),
                "This machine has the Finder command, so the unavailable path cannot be exercised.");
        Path repo = gitRepo("reveal-unavailable");
        Files.writeString(repo.resolve("file.txt"), "ok\n");
        String key = ProjectKey.of(repo.toString()).encoded();
        when(catalog.summaries()).thenReturn(List.of(summary(repo, scope(repo.toString()))));

        assertThatThrownBy(() -> service().revealInFinder(new CodeReference(key, "file.txt", null, null, null)))
                .isInstanceOf(CodeNavigationException.class)
                .satisfies(ex -> assertThat(((CodeNavigationException) ex).code())
                        .isEqualTo(CodeNavigationError.REVEAL_UNAVAILABLE));
        assertThat(launcher.commands).isEmpty();
    }

    /** Mirrors the precondition CodeNavigationService checks before it launches a reveal. */
    private static boolean finderCommandPresent() {
        Path command = Path.of("/usr/bin/open");
        return Files.isRegularFile(command) && Files.isExecutable(command);
    }

    private CodeNavigationService service() {
        return new CodeNavigationService(new CodeScopeResolver(catalog), launcher, properties);
    }

    private Path gitRepo(String name) throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve(name));
        Path git = Files.createDirectories(repo.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        return repo;
    }

    private Path gitWorktree(Path primary, String name) throws Exception {
        Path worktree = Files.createDirectories(tempDir.resolve(name));
        Path gitDirectory = Files.createDirectories(primary.resolve(".git/worktrees").resolve(name));
        Files.writeString(gitDirectory.resolve("HEAD"), "ref: refs/heads/" + name + "\n");
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDirectory + "\n");
        return worktree;
    }

    private static ProjectSummary summary(Path canonical, ProjectScope... scopes) {
        return summaryWithSessionCount(canonical, 1, scopes);
    }

    private static ProjectSummary summaryWithSessionCount(
            Path canonical,
            long sessionCount,
            ProjectScope... scopes) {
        return new ProjectSummary(
                ProjectKey.of(canonical.toString()).encoded(),
                canonical.toString(),
                canonical.getFileName() == null ? canonical.toString() : canonical.getFileName().toString(),
                sessionCount,
                1,
                0,
                Instant.parse("2026-07-29T12:00:00Z"),
                Instant.parse("2026-07-29T12:00:00Z"),
                List.of(scopes));
    }

    private static ProjectScope scope(String path) {
        return new ProjectScope(ProjectKey.of(path).encoded(), path, path, true, null);
    }

    private static void assertCode(
            CodeNavigationService service,
            CodeReference reference,
            CodeNavigationError expected) {
        assertThatThrownBy(() -> service.openInEditor(reference))
                .isInstanceOfSatisfying(CodeNavigationException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    private static final class CapturingLauncher implements CommandLauncher {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        public void launch(List<String> command, java.time.Duration timeout) {
            commands.add(List.copyOf(command));
        }
    }
}
