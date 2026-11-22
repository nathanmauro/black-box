package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.BroadcastChanged;
import dev.nathan.sbaagentic.memory.ProjectBroadcast;
import dev.nathan.sbaagentic.memory.internal.application.port.BroadcastStore;
import dev.nathan.sbaagentic.memory.internal.application.port.BroadcastStore.StoredBroadcast;
import dev.nathan.sbaagentic.memory.internal.application.port.BroadcastStore.Visibility;
import dev.nathan.sbaagentic.memory.internal.domain.BroadcastDigest;
import dev.nathan.sbaagentic.memory.internal.domain.BroadcastKind;
import dev.nathan.sbaagentic.memory.internal.domain.BroadcastSeverity;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.recording.ProjectScopeResolver;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-project broadcasts: the mutable live set every concurrent or newly started session in a
 * project should see. Decisions are immutable and handoffs are snapshots; a broadcast is added,
 * refreshed by key, and subtracted when its condition ends.
 *
 * <p>Tentacles carry the "started but not finished" contract: only an explicit clear closes one.
 * A tentacle's TTL hides it from recall once it lapses, but the stale sweep keeps reporting it, so
 * an unfinished thread can fall out of view without falling out of tracking.
 */
@Service
public class BroadcastService implements RecallBroadcastSource {

    static final int MAX_MESSAGE_CHARS = 280;
    static final int MAX_TTL_HOURS = 24 * 365;
    static final int DEFAULT_STALE_AFTER_HOURS = 72;
    static final int DEFAULT_LIST_LIMIT = 50;
    static final int MAX_LIST_LIMIT = 200;

    /** Upper bound on rows read before in-memory staleness filtering; broadcast sets are small. */
    private static final int SCAN_LIMIT = 1_000;

    private static final int MAX_SOURCE_CHARS = 40;
    private static final int MAX_SESSION_CHARS = 200;
    private static final int MAX_REASON_CHARS = 500;
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,79}");

    private static final Comparator<ProjectBroadcast> LONGEST_NEGLECTED_FIRST =
            Comparator.comparing(ProjectBroadcast::updatedAt).thenComparing(ProjectBroadcast::key);

    private final BroadcastStore store;
    private final ProjectScopeResolver projects;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public BroadcastService(
            BroadcastStore store, ProjectScopeResolver projects, Clock clock, ApplicationEventPublisher events) {
        this.store = store;
        this.projects = projects;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public ProjectBroadcast set(BroadcastUpsert command) {
        if (command == null) {
            throw new IllegalArgumentException("Broadcast request is required.");
        }
        Instant now = now();
        BroadcastKind kind = BroadcastKind.parseOrDefault(command.kind());
        BroadcastSeverity severity = BroadcastSeverity.parseOrDefault(command.severity(), kind.defaultSeverity());

        StoredBroadcast candidate = new StoredBroadcast(
                UUID.randomUUID().toString(),
                canonicalScope(command.repo()),
                key(command.key()),
                kind.wireName(),
                message(command.message()),
                severity.wireName(),
                source(command.source()),
                bounded(command.clientSessionId(), MAX_SESSION_CHARS, "clientSessionId"),
                now,
                now,
                expiresAt(kind, command.ttlHours(), now),
                followUpAt(command.followUpAt()),
                null,
                null,
                null);

        ProjectBroadcast saved = view(store.upsert(candidate), now, defaultStaleAfter());
        announce(saved, BroadcastChanged.CHANGE_SET);

        return saved;
    }

    @Transactional
    public BroadcastClearResult clear(BroadcastClear command) {
        if (command == null) {
            throw new IllegalArgumentException("Clear request is required.");
        }
        Instant now = now();
        String scope = canonicalScope(command.repo());
        String key = key(command.key());
        String clearedBy = clearedBy(command.source(), command.clientSessionId());
        String reason = bounded(command.reason(), MAX_REASON_CHARS, "reason");

        var closed = store.clear(scope, key, now, clearedBy, reason);
        if (closed.isPresent()) {
            ProjectBroadcast cleared = view(closed.get(), now, defaultStaleAfter());
            announce(cleared, BroadcastChanged.CHANGE_CLEARED);

            return new BroadcastClearResult(scope, key, true, cleared);
        }

        // Idempotent: a repeated clear reports the earlier one instead of failing.
        ProjectBroadcast previous = store.latestCleared(scope, key)
                .map(row -> view(row, now, defaultStaleAfter()))
                .orElse(null);

        return new BroadcastClearResult(scope, key, false, previous);
    }

    public BroadcastListResponse list(BroadcastListQuery query) {
        BroadcastListQuery filters =
                query == null ? new BroadcastListQuery(null, null, null, null, null, null, null) : query;
        Instant now = now();
        boolean allProjects = Boolean.TRUE.equals(filters.allProjects());
        boolean staleOnly = Boolean.TRUE.equals(filters.staleOnly());
        int staleAfterHours = staleAfterHours(filters.staleAfterHours());
        Duration staleAfter = Duration.ofHours(staleAfterHours);

        List<String> scopes = allProjects ? null : requiredScopes(filters.project());
        String kind = kindFilter(filters.kind(), staleOnly);
        Visibility visibility = Boolean.TRUE.equals(filters.includeCleared()) ? Visibility.ALL : Visibility.OPEN;

        List<ProjectBroadcast> broadcasts = new ArrayList<>();
        for (StoredBroadcast row : store.find(scopes, kind, visibility, now, SCAN_LIMIT)) {
            ProjectBroadcast broadcast = view(row, now, staleAfter);
            if (!staleOnly || broadcast.stale()) {
                broadcasts.add(broadcast);
            }
        }
        broadcasts.sort(staleOnly ? LONGEST_NEGLECTED_FIRST : BroadcastDigest.PRIORITY);
        List<ProjectBroadcast> page =
                broadcasts.stream().limit(listLimit(filters.limit())).toList();

        return new BroadcastListResponse(page, page.size(), staleAfterHours, now);
    }

    @Override
    public BroadcastDigest digestFor(List<String> projectScopes) {
        if (projectScopes == null || projectScopes.isEmpty()) {

            return BroadcastDigest.EMPTY;
        }
        Instant now = now();
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : projectScopes) {
            if (scope != null && !scope.isBlank()) {
                scopes.add(ProjectKey.of(scope).value());
            }
        }
        if (scopes.isEmpty()) {

            return BroadcastDigest.EMPTY;
        }

        List<ProjectBroadcast> active = store.find(scopes, null, Visibility.ACTIVE, now, SCAN_LIMIT).stream()
                .map(row -> view(row, now, defaultStaleAfter()))
                .toList();

        return BroadcastDigest.select(active);
    }

    // Listeners that touch live clients run after commit, so a refresh never reads ahead of it.
    private void announce(ProjectBroadcast broadcast, String change) {
        events.publishEvent(new BroadcastChanged(broadcast.projectScope(), broadcast.key(), broadcast.kind(), change));
    }

    private ProjectBroadcast view(StoredBroadcast row, Instant now, Duration staleAfter) {

        return new ProjectBroadcast(
                row.id(),
                row.projectScope(),
                row.key(),
                row.kind(),
                row.message(),
                row.severity(),
                row.source(),
                row.clientSessionId(),
                row.createdAt(),
                row.updatedAt(),
                row.expiresAt(),
                row.followUpAt(),
                row.clearedAt(),
                row.clearedBy(),
                row.clearReason(),
                status(row, now),
                stale(row, now, staleAfter));
    }

    private static String status(StoredBroadcast row, Instant now) {
        if (row.clearedAt() != null) {

            return ProjectBroadcast.STATUS_CLEARED;
        }
        if (expired(row, now)) {

            return ProjectBroadcast.STATUS_EXPIRED;
        }

        return ProjectBroadcast.STATUS_ACTIVE;
    }

    /**
     * A tentacle is stale when it lapsed while still open, when its explicit follow-up has passed,
     * or, with no follow-up set, when nobody has touched it within the threshold. An explicit
     * follow-up overrides the age rule: the agent that set it already said when to look again.
     */
    private static boolean stale(StoredBroadcast row, Instant now, Duration staleAfter) {
        if (row.clearedAt() != null || !BroadcastKind.TENTACLE.wireName().equals(row.kind())) {

            return false;
        }
        if (expired(row, now)) {

            return true;
        }
        if (row.followUpAt() != null) {

            return !row.followUpAt().isAfter(now);
        }

        return !row.updatedAt().plus(staleAfter).isAfter(now);
    }

    private static boolean expired(StoredBroadcast row, Instant now) {

        return row.expiresAt() != null && !row.expiresAt().isAfter(now);
    }

    private String canonicalScope(String repo) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo is required: the working directory or project alias.");
        }
        List<String> resolved = projects.scopesFor(repo.strip());
        String canonical = resolved == null || resolved.isEmpty() ? repo.strip() : resolved.getFirst();

        return ProjectKey.of(canonical).value();
    }

    private List<String> requiredScopes(String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project is required unless allProjects is true.");
        }
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(ProjectKey.of(project).value());
        List<String> resolved = projects.scopesFor(project.strip());
        if (resolved != null) {
            resolved.forEach(scope -> scopes.add(ProjectKey.of(scope).value()));
        }

        return List.copyOf(scopes);
    }

    private static String kindFilter(String kind, boolean staleOnly) {
        if (kind == null || kind.isBlank()) {

            return staleOnly ? BroadcastKind.TENTACLE.wireName() : null;
        }
        BroadcastKind parsed = BroadcastKind.parse(kind);
        if (staleOnly && parsed != BroadcastKind.TENTACLE) {
            throw new IllegalArgumentException("staleOnly applies to tentacles only.");
        }

        return parsed.wireName();
    }

    private static String key(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "key must be a slug of 1-80 characters: letters, digits, '.', '_', ':', or '-'.");
        }

        return normalized;
    }

    private static String message(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("message is required.");
        }
        if (stripped.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("message must be at most " + MAX_MESSAGE_CHARS
                    + " characters; put detail in a handoff or observation and reference it.");
        }

        return stripped;
    }

    private static String source(String value) {
        String normalized =
                value == null || value.isBlank() ? "manual" : value.strip().toLowerCase(Locale.ROOT);

        return bounded(normalized, MAX_SOURCE_CHARS, "source");
    }

    private static String clearedBy(String source, String clientSessionId) {
        String session = bounded(clientSessionId, MAX_SESSION_CHARS, "clientSessionId");

        return session == null ? source(source) : source(source) + ":" + session;
    }

    private static String bounded(String value, int maxChars, String field) {
        if (value == null || value.isBlank()) {

            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > maxChars) {
            throw new IllegalArgumentException(field + " must be at most " + maxChars + " characters.");
        }

        return stripped;
    }

    private static Instant expiresAt(BroadcastKind kind, Integer ttlHours, Instant now) {
        if (ttlHours == null) {

            return kind.defaultTtl().map(now::plus).orElse(null);
        }
        if (ttlHours < 0 || ttlHours > MAX_TTL_HOURS) {
            throw new IllegalArgumentException("ttlHours must be between 0 (sticky) and " + MAX_TTL_HOURS + ".");
        }

        return ttlHours == 0 ? null : now.plus(ttlHours, ChronoUnit.HOURS);
    }

    static Instant followUpAt(String value) {
        if (value == null || value.isBlank()) {

            return null;
        }
        String stripped = value.strip();
        try {

            return OffsetDateTime.parse(stripped).toInstant().truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeException ignored) {
            // Fall through to a bare date.
        }
        try {

            return LocalDate.parse(stripped).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("followUpAt must be an ISO-8601 date-time with offset or a date.");
        }
    }

    private static int staleAfterHours(Integer value) {
        if (value == null || value <= 0) {

            return DEFAULT_STALE_AFTER_HOURS;
        }

        return Math.min(value, MAX_TTL_HOURS);
    }

    private static int listLimit(Integer value) {
        if (value == null || value <= 0) {

            return DEFAULT_LIST_LIMIT;
        }

        return Math.min(value, MAX_LIST_LIMIT);
    }

    private static Duration defaultStaleAfter() {

        return Duration.ofHours(DEFAULT_STALE_AFTER_HOURS);
    }

    /** Stored timestamps have millisecond precision, so reads compare equal to what was written. */
    private Instant now() {

        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
