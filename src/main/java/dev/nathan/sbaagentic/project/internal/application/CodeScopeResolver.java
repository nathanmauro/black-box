package dev.nathan.sbaagentic.project.internal.application;

import dev.nathan.sbaagentic.project.CodeProjectScope;
import dev.nathan.sbaagentic.project.CodeReference;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.project.ProjectScope;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectCatalogStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CodeScopeResolver {

    private static final String GITDIR_PREFIX = "gitdir:";

    private final ProjectCatalogStore catalog;

    public CodeScopeResolver(ProjectCatalogStore catalog) {
        this.catalog = catalog;
    }

    public List<CodeProjectScope> codeScopes() {
        Map<String, VerifiedScope> verified = verifiedScopes();

        return verified.values().stream()
                .map(scope -> new CodeProjectScope(
                        scope.projectKey(), scope.catalogRoot().toString()))
                .sorted(Comparator.comparingInt(
                                (CodeProjectScope scope) -> scope.root().length())
                        .reversed()
                        .thenComparing(CodeProjectScope::root)
                        .thenComparing(CodeProjectScope::projectKey))
                .toList();
    }

    ResolvedCodeTarget resolve(CodeReference reference) {
        if (reference == null
                || reference.projectKey() == null
                || reference.projectKey().isBlank()
                || reference.relativePath() == null
                || reference.relativePath().isBlank()) {
            throw failure(CodeNavigationError.INVALID_REFERENCE, "A project key and relative file path are required.");
        }

        VerifiedScope scope = Optional.ofNullable(verifiedScopes().get(reference.projectKey()))
                .orElseThrow(() -> failure(
                        CodeNavigationError.PROJECT_UNRESOLVED, "The project is not an eligible code workspace."));
        Path relative = parseRelative(reference.relativePath());
        Path lexicalTarget = scope.realRoot().resolve(relative).normalize();
        if (!lexicalTarget.startsWith(scope.realRoot())) {
            throw outsideRoot();
        }
        if (!Files.isRegularFile(lexicalTarget) || !Files.isReadable(lexicalTarget)) {
            throw failure(CodeNavigationError.FILE_MISSING, "The referenced file does not exist.");
        }

        Path realTarget;
        try {
            realTarget = lexicalTarget.toRealPath();
        } catch (IOException ex) {
            throw failure(CodeNavigationError.FILE_MISSING, "The referenced file does not exist.", ex);
        }
        if (!realTarget.startsWith(scope.realRoot())) {
            throw outsideRoot();
        }

        validatePosition(realTarget, reference.line(), reference.column());

        return new ResolvedCodeTarget(
                scope.realRoot(), scope.workspaceRoot(), realTarget, reference.line(), reference.column());
    }

    private Map<String, VerifiedScope> verifiedScopes() {
        Map<String, VerifiedScope> scopes = new LinkedHashMap<>();
        List<ProjectSummary> summaries = catalog.summaries();
        if (summaries == null) {

            return scopes;
        }
        for (ProjectSummary summary : summaries) {
            if (summary == null || summary.sessionCount() < 1) {
                continue;
            }
            for (ProjectScope candidate : scopesFor(summary)) {
                verify(candidate).ifPresent(scope -> scopes.putIfAbsent(scope.projectKey(), scope));
            }
        }

        return scopes;
    }

    private static List<ProjectScope> scopesFor(ProjectSummary summary) {
        if (summary == null) {

            return List.of();
        }
        if (summary.scopes() != null && !summary.scopes().isEmpty()) {

            return summary.scopes();
        }

        return List.of(new ProjectScope(summary.projectKey(), summary.canonicalKey(), summary.label(), true, null));
    }

    private static Optional<VerifiedScope> verify(ProjectScope scope) {
        if (scope == null
                || scope.projectKey() == null
                || scope.projectKey().isBlank()
                || scope.canonicalKey() == null
                || scope.canonicalKey().isBlank()
                || ProjectKey.NO_PROJECT.equals(scope.canonicalKey())) {

            return Optional.empty();
        }

        try {
            Path catalogRoot = Path.of(scope.canonicalKey()).normalize();
            if (!catalogRoot.isAbsolute() || isBroadRoot(catalogRoot)) {

                return Optional.empty();
            }
            Path realRoot = catalogRoot.toRealPath();
            if (!Files.isDirectory(realRoot) || isBroadRoot(realRoot)) {

                return Optional.empty();
            }
            Optional<Path> workspace = containingGitWorkspace(realRoot);
            if (workspace.isEmpty() || isBroadRoot(workspace.get())) {

                return Optional.empty();
            }

            return Optional.of(new VerifiedScope(scope.projectKey(), catalogRoot, realRoot, workspace.get()));
        } catch (InvalidPathException | IOException | SecurityException ex) {

            return Optional.empty();
        }
    }

    private static Optional<Path> containingGitWorkspace(Path start) throws IOException {
        Path current = start;
        while (current != null) {
            if (hasValidGitMarker(current)) {

                return Optional.of(current.toRealPath());
            }
            current = current.getParent();
        }

        return Optional.empty();
    }

    private static boolean hasValidGitMarker(Path directory) {
        Path marker = directory.resolve(".git");
        if (Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)) {

            return Files.isRegularFile(marker.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS);
        }
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {

            return false;
        }
        try {
            String contents = Files.readString(marker).trim();
            if (!contents.startsWith(GITDIR_PREFIX)) {

                return false;
            }
            String value = contents.substring(GITDIR_PREFIX.length()).trim();
            if (value.isEmpty()) {

                return false;
            }
            Path gitDirectory = Path.of(value);
            if (!gitDirectory.isAbsolute()) {
                gitDirectory = directory.resolve(gitDirectory);
            }
            Path realGitDirectory = gitDirectory.normalize().toRealPath();

            return Files.isDirectory(realGitDirectory)
                    && Files.isRegularFile(realGitDirectory.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | InvalidPathException | SecurityException ex) {

            return false;
        }
    }

    private static boolean isBroadRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {

            return true;
        }
        try {
            Path home = Path.of(System.getProperty("user.home")).toRealPath();

            return normalized.equals(home);
        } catch (IOException | InvalidPathException | SecurityException ex) {
            Path home =
                    Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

            return normalized.equals(home);
        }
    }

    private static Path parseRelative(String value) {
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute()) {
                throw outsideRoot();
            }
            for (Path part : relative) {
                if ("..".equals(part.toString())) {
                    throw outsideRoot();
                }
            }

            return relative;
        } catch (InvalidPathException ex) {
            throw failure(CodeNavigationError.INVALID_REFERENCE, "The file path is invalid.", ex);
        }
    }

    private static void validatePosition(Path target, Integer line, Integer column) {
        if (line == null && column == null) {

            return;
        }
        if (line == null || line < 1 || (column != null && column < 1)) {
            throw failure(CodeNavigationError.INVALID_REFERENCE, "Line and column positions must be one-based.");
        }

        String selectedLine = null;
        int linesRead = 0;
        try (BufferedReader reader = Files.newBufferedReader(target)) {
            for (int current = 1; current <= line; current++) {
                selectedLine = reader.readLine();
                if (selectedLine == null) {
                    break;
                }
                linesRead = current;
            }
        } catch (IOException ex) {
            throw failure(CodeNavigationError.FILE_MISSING, "The referenced file cannot be read.", ex);
        }
        if (selectedLine == null
                && ((line == 1 && isEmpty(target)) || (line == linesRead + 1 && endsWithLineBreak(target)))) {
            selectedLine = "";
        }
        if (selectedLine == null || (column != null && column > selectedLine.length() + 1)) {
            throw failure(CodeNavigationError.INVALID_REFERENCE, "The requested line or column is outside the file.");
        }
    }

    private static boolean isEmpty(Path target) {
        try {

            return Files.size(target) == 0;
        } catch (IOException ex) {

            return false;
        }
    }

    private static boolean endsWithLineBreak(Path target) {
        try (SeekableByteChannel channel = Files.newByteChannel(target, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < 1) {

                return false;
            }
            ByteBuffer lastByte = ByteBuffer.allocate(1);
            channel.position(size - 1);
            if (channel.read(lastByte) != 1) {

                return false;
            }
            byte value = lastByte.array()[0];

            return value == '\n' || value == '\r';
        } catch (IOException ex) {

            return false;
        }
    }

    private static CodeNavigationException outsideRoot() {

        return failure(CodeNavigationError.OUTSIDE_PROJECT_ROOT, "The file reference leaves its known project root.");
    }

    private static CodeNavigationException failure(CodeNavigationError code, String message) {

        return new CodeNavigationException(code, message);
    }

    private static CodeNavigationException failure(CodeNavigationError code, String message, Throwable cause) {

        return new CodeNavigationException(code, message, cause);
    }

    private record VerifiedScope(String projectKey, Path catalogRoot, Path realRoot, Path workspaceRoot) {}
}
