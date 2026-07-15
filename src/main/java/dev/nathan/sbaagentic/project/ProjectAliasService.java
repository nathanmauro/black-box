package dev.nathan.sbaagentic.project;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectAliasService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAliasService.class);

    static final String MANUAL_SOURCE = "manual";
    static final String NESTED_WORKTREE_SOURCE = "nested-worktree";
    static final String GIT_COMMONDIR_SOURCE = "git-commondir";

    private static final List<String> NESTED_WORKTREE_MARKERS = List.of(
            "/.claude/worktrees/",
            "/.worktrees/");

    private final ProjectAliasRepository repository;

    public ProjectAliasService(ProjectAliasRepository repository) {
        this.repository = repository;
    }

    public String resolve(String scope) {
        return snapshot().resolve(scope);
    }

    public List<String> scopesFor(String scope) {
        return snapshot().scopesFor(scope);
    }

    public List<ProjectScope> projectScopesFor(String scope) {
        return snapshot().projectScopesFor(scope);
    }

    Snapshot snapshot() {
        return new Snapshot(repository.findAll());
    }

    /**
     * Alias validation and persistence are one graph mutation. Black Box runs as a single local
     * service, so serializing mutations on this singleton prevents two inverse writes from both
     * passing cycle validation before either row is visible.
     */
    public synchronized ProjectAlias put(ProjectAliasRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Project alias request is required.");
        }
        String aliasKey = requiredScope(request.aliasKey(), "Alias key");
        String requestedCanonical = requiredScope(request.canonicalKey(), "Canonical key");
        rejectProtected(aliasKey);
        rejectProtected(requestedCanonical);

        String canonicalKey = resolve(requestedCanonical);
        if (aliasKey.equals(canonicalKey)) {
            throw new IllegalArgumentException("Alias and canonical project scopes must differ.");
        }
        Optional<ProjectAlias> existing = repository.findByAliasKey(aliasKey);
        if (existing.isPresent()) {
            ProjectAlias alias = existing.get();
            if (requestedCanonical.equals(alias.canonicalKey())
                    || canonicalKey.equals(resolve(alias.canonicalKey()))) {
                return alias;
            }
            throw new ResponseStatusException(
                    CONFLICT,
                    "Project scope is already aliased to " + alias.canonicalKey());
        }

        try {
            return repository.insert(
                    UUID.randomUUID().toString(),
                    aliasKey,
                    canonicalKey,
                    MANUAL_SOURCE,
                    Instant.now());
        }
        catch (DataIntegrityViolationException ex) {
            Optional<ProjectAlias> concurrent = repository.findByAliasKey(aliasKey);
            if (concurrent.isPresent() && canonicalKey.equals(concurrent.get().canonicalKey())) {
                return concurrent.get();
            }
            if (concurrent.isPresent()) {
                throw new ResponseStatusException(
                        CONFLICT,
                        "Project scope is already aliased to " + concurrent.get().canonicalKey());
            }
            throw ex;
        }
    }

    public synchronized void delete(String aliasKey) {
        String normalized = requiredScope(aliasKey, "Alias key");
        ProjectAlias existing = repository.findByAliasKey(normalized)
                .orElseThrow(() -> new ResponseStatusException(
                        NOT_FOUND,
                        "Unknown project alias: " + normalized));
        if (!MANUAL_SOURCE.equals(existing.source())) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Automatically discovered project aliases cannot be deleted.");
        }
        if (repository.delete(normalized) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown project alias: " + normalized);
        }
    }

    /**
     * Persists only aliases whose owning repository is encoded by the worktree itself and backed by
     * Git metadata. Ambiguous basename-only layouts (for example
     * {@code ~/.codex/worktrees/<id>/<name>}) are deliberately left untouched unless their
     * still-existing Git metadata identifies a common directory.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void discoverVerifiedAliases() {
        for (String observedScope : repository.distinctObservedScopes()) {
            discoverVerifiedAlias(observedScope);
        }
    }

    public void discoverVerifiedAlias(String observedScope) {
        try {
            if (observedScope == null || observedScope.isBlank() || protectedScope(observedScope)) {
                return;
            }
            Optional<String> gitOwner = linkedGitOwner(observedScope);
            if (gitOwner.isPresent()) {
                persistDiscovered(observedScope, gitOwner.get(), GIT_COMMONDIR_SOURCE);
                return;
            }
            nestedWorktreeOwner(observedScope)
                    .ifPresent(owner -> persistDiscovered(observedScope, owner, NESTED_WORKTREE_SOURCE));
        }
        catch (RuntimeException ex) {
            // Alias discovery is supporting metadata. It must never turn a successfully persisted
            // event into an ingestion failure; the raw project scope remains fully usable.
            LOGGER.warn("Unable to discover a verified project alias for scope {}", observedScope, ex);
        }
    }

    private synchronized void persistDiscovered(String aliasScope, String canonicalScope, String source) {
        String aliasKey = ProjectKeyCodec.canonicalize(aliasScope);
        String canonicalKey = ProjectKeyCodec.canonicalize(canonicalScope);
        if (protectedScope(aliasKey) || protectedScope(canonicalKey) || aliasKey.equals(canonicalKey)) {
            return;
        }
        if (aliasKey.equals(resolve(canonicalKey))) {
            LOGGER.warn(
                    "Skipping discovered project alias {} -> {} because it would create a cycle",
                    aliasKey,
                    canonicalKey);
            return;
        }
        Optional<ProjectAlias> existing = repository.findByAliasKey(aliasKey);
        if (existing.isPresent()) {
            return;
        }
        try {
            repository.insert(
                    UUID.randomUUID().toString(),
                    aliasKey,
                    canonicalKey,
                    source,
                    Instant.now());
        }
        catch (DataIntegrityViolationException ignored) {
            // Concurrent ingestion can discover the same worktree twice. The unique alias key is
            // authoritative; discovery never replaces whichever mapping committed first.
        }
    }

    private Optional<String> nestedWorktreeOwner(String scope) {
        int nearestMarkerAt = -1;
        String nearestMarker = null;
        for (String marker : NESTED_WORKTREE_MARKERS) {
            int markerAt = scope.lastIndexOf(marker);
            if (markerAt > nearestMarkerAt) {
                nearestMarkerAt = markerAt;
                nearestMarker = marker;
            }
        }
        if (nearestMarkerAt <= 0
                || nearestMarker == null
                || nearestMarkerAt + nearestMarker.length() >= scope.length()) {
            return Optional.empty();
        }

        try {
            Path owner = Path.of(scope.substring(0, nearestMarkerAt)).normalize().toAbsolutePath();
            Path home = Path.of(System.getProperty("user.home")).normalize().toAbsolutePath();
            Path dotGit = owner.resolve(".git");
            if (owner.equals(home)
                    || protectedScope(owner.toString())
                    || (!Files.isDirectory(dotGit) && !Files.isRegularFile(dotGit))) {
                return Optional.empty();
            }
            return Optional.of(owner.toString());
        }
        catch (InvalidPathException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> linkedGitOwner(String scope) {
        try {
            Path candidate = Path.of(scope);
            if (!candidate.isAbsolute() || !Files.exists(candidate)) {
                return Optional.empty();
            }
            Path current = Files.isDirectory(candidate) ? candidate : candidate.getParent();
            while (current != null) {
                Path dotGit = current.resolve(".git");
                if (Files.isRegularFile(dotGit)) {
                    return ownerFromGitFile(dotGit, current);
                }
                if (Files.isDirectory(dotGit)) {
                    return Optional.empty();
                }
                current = current.getParent();
            }
        }
        catch (InvalidPathException | SecurityException ignored) {
            // An ingested cwd is untrusted historical data. Invalid or inaccessible paths remain
            // separate scopes and can still be explicitly aliased later.
        }
        return Optional.empty();
    }

    private Optional<String> ownerFromGitFile(Path dotGit, Path worktreeRoot) {
        try {
            String pointer = Files.readString(dotGit).trim();
            if (!pointer.startsWith("gitdir:")) {
                return Optional.empty();
            }
            Path gitDir = Path.of(pointer.substring("gitdir:".length()).trim());
            if (!gitDir.isAbsolute()) {
                gitDir = worktreeRoot.resolve(gitDir);
            }
            gitDir = gitDir.normalize().toAbsolutePath();
            Path commonDirFile = gitDir.resolve("commondir");
            if (!Files.isRegularFile(commonDirFile)) {
                return Optional.empty();
            }
            Path commonDir = Path.of(Files.readString(commonDirFile).trim());
            if (!commonDir.isAbsolute()) {
                commonDir = gitDir.resolve(commonDir);
            }
            commonDir = commonDir.normalize().toAbsolutePath();
            if (!Files.isDirectory(commonDir)
                    || commonDir.getFileName() == null
                    || !".git".equals(commonDir.getFileName().toString())
                    || !gitDir.startsWith(commonDir.resolve("worktrees"))) {
                return Optional.empty();
            }
            Path owner = commonDir.getParent();
            if (owner == null || owner.equals(worktreeRoot) || protectedScope(owner.toString())) {
                return Optional.empty();
            }
            return Optional.of(owner.toString());
        }
        catch (IOException | InvalidPathException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private static String requiredScope(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return ProjectKeyCodec.canonicalize(value);
    }

    private static void rejectProtected(String scope) {
        if (protectedScope(scope)) {
            throw new IllegalArgumentException("Protected project scope cannot be aliased: " + scope);
        }
    }

    private static boolean protectedScope(String scope) {
        String normalized = ProjectKeyCodec.canonicalize(scope);
        return "/".equals(normalized) || ProjectKeyCodec.NO_PROJECT_KEY.equals(normalized);
    }

    /** Immutable request-level view used to expand alias chains without repeated JDBC lookups. */
    final class Snapshot {

        private final List<ProjectAlias> aliases;
        private final Map<String, ProjectAlias> aliasesByKey;
        private final Map<String, String> resolved = new HashMap<>();

        private Snapshot(List<ProjectAlias> aliases) {
            this.aliases = List.copyOf(aliases);
            Map<String, ProjectAlias> indexed = new LinkedHashMap<>();
            for (ProjectAlias alias : aliases) {
                indexed.put(ProjectKeyCodec.canonicalize(alias.aliasKey()), alias);
            }
            this.aliasesByKey = Map.copyOf(indexed);
        }

        String resolve(String scope) {
            String normalized = ProjectKeyCodec.canonicalize(scope);
            String cached = resolved.get(normalized);
            if (cached != null) {
                return cached;
            }

            Set<String> seen = new LinkedHashSet<>();
            String current = normalized;
            while (seen.add(current)) {
                String cachedCurrent = resolved.get(current);
                if (cachedCurrent != null) {
                    current = cachedCurrent;
                    break;
                }
                ProjectAlias alias = aliasesByKey.get(current);
                if (alias == null) {
                    break;
                }
                current = ProjectKeyCodec.canonicalize(alias.canonicalKey());
            }
            if (seen.contains(current) && aliasesByKey.containsKey(current)) {
                throw new IllegalStateException("Project alias cycle detected for " + normalized);
            }
            for (String visited : seen) {
                resolved.put(visited, current);
            }
            return current;
        }

        List<String> scopesFor(String scope) {
            String canonical = resolve(scope);
            LinkedHashSet<String> scopes = new LinkedHashSet<>();
            scopes.add(canonical);
            aliases.stream()
                    .filter(alias -> resolvesTo(alias.aliasKey(), canonical))
                    .map(ProjectAlias::aliasKey)
                    .map(ProjectKeyCodec::canonicalize)
                    .forEach(scopes::add);
            return List.copyOf(scopes);
        }

        List<ProjectScope> projectScopesFor(String scope) {
            String canonical = resolve(scope);
            List<ProjectScope> scopes = new ArrayList<>();
            scopes.add(new ProjectScope(
                    ProjectKeyCodec.encode(canonical),
                    canonical,
                    ProjectKeyCodec.labelFor(canonical),
                    true,
                    null));
            aliases.stream()
                    .filter(alias -> resolvesTo(alias.aliasKey(), canonical))
                    .map(alias -> {
                        String aliasKey = ProjectKeyCodec.canonicalize(alias.aliasKey());
                        return new ProjectScope(
                                ProjectKeyCodec.encode(aliasKey),
                                aliasKey,
                                ProjectKeyCodec.labelFor(aliasKey),
                                false,
                                alias.source());
                    })
                    .forEach(scopes::add);
            return List.copyOf(scopes);
        }

        private boolean resolvesTo(String scope, String canonical) {
            try {
                return canonical.equals(resolve(scope));
            }
            catch (IllegalStateException ex) {
                LOGGER.warn("Ignoring cyclic project alias while expanding scope {}", scope, ex);
                return false;
            }
        }
    }
}
