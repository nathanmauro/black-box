package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader;
import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader.TypedEvent;
import dev.nathan.sbaagentic.memory.internal.domain.IdeaObservationParser;
import dev.nathan.sbaagentic.memory.internal.domain.IdeaObservationParser.ParsedIdea;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.Ideas;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.ProjectScopeResolver;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.Titles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Reads the {@code Idea} capture kind back as a list of ideas (latest event per {@code ideaKey})
 * and migrates the {@code [Idea]} observations agents wrote before the kind existed.
 */
@Service
public class IdeaService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    /** Upper bound on events scanned per request; ideas are deliberate captures, not a firehose. */
    static final int SCAN_LIMIT = 5_000;

    static final String MIGRATION_CLIENT_SESSION_ID = "idea-migration";
    private static final String OBSERVATION_EVENT_TYPE = "Observation";

    private final IdeaEventReader reader;
    private final RecordingCaptureOperations captureOperations;
    private final ProjectScopeResolver projectScopes;

    public IdeaService(
            IdeaEventReader reader, RecordingCaptureOperations captureOperations, ProjectScopeResolver projectScopes) {
        this.reader = reader;
        this.captureOperations = captureOperations;
        this.projectScopes = projectScopes;
    }

    public IdeaListResponse list(IdeaListQuery query) {
        IdeaListQuery q = query == null ? new IdeaListQuery(null, null, null, null, null, null) : query;
        Set<String> statuses = normalizedStatuses(q.statuses());
        String origin = normalizedOrigin(q.origin());
        Set<String> scopes = resolveScopes(q.project(), q.repo());
        List<String> terms = terms(q.q());
        int limit = clampLimit(q.limit());

        List<IdeaView> items = new ArrayList<>();
        for (IdeaView idea : collapsed()) {
            if (!statuses.isEmpty() && !statuses.contains(idea.status())) {
                continue;
            }
            if (origin != null && !origin.equals(idea.origin())) {
                continue;
            }
            if (scopes != null && !inScope(idea, scopes)) {
                continue;
            }
            if (!terms.isEmpty() && !matchesTerms(idea, terms)) {
                continue;
            }
            items.add(idea);
            if (items.size() == limit) {
                break;
            }
        }

        return new IdeaListResponse(List.copyOf(items), items.size());
    }

    /**
     * Parses every observation whose text starts with {@code [Idea]}. With {@code apply=false}
     * nothing is written. With {@code apply=true} each not-yet-migrated candidate is captured as a
     * new {@code Idea} whose metadata {@code migratedFrom} is the observation's event id, so a
     * repeat run skips it. Observations are never modified or deleted.
     */
    public synchronized IdeaMigrationResult migrateObservations(boolean apply) {
        Map<String, String> migrated = migratedObservationIds();
        List<TypedEvent> observations =
                newestFirst(reader.eventsOfType(OBSERVATION_EVENT_TYPE, Ideas.TEXT_PREFIX, SCAN_LIMIT));

        List<IdeaMigrationResult.Candidate> candidates = new ArrayList<>();
        for (TypedEvent row : observations) {
            AgentEvent observation = row.event();
            if (observation == null || !IdeaObservationParser.isIdeaObservation(observation.text())) {
                continue;
            }
            ParsedIdea parsed = IdeaObservationParser.parse(observation.text());
            String repo = firstNonBlank(str(metadata(observation).get("repo")), row.cwd());
            CaptureIdeaRequest idea = new CaptureIdeaRequest(
                    observation.source(),
                    MIGRATION_CLIENT_SESSION_ID,
                    repo,
                    parsed.title(),
                    parsed.oneLiner(),
                    parsed.origin(),
                    parsed.quote(),
                    observation.sessionId(),
                    parsed.legs(),
                    parsed.status(),
                    parsed.connects(),
                    null,
                    null,
                    observation.text(),
                    parsed.title() == null ? null : Ideas.defaultKey(repo, parsed.title()));
            String existing = migrated.get(observation.id());
            candidates.add(new IdeaMigrationResult.Candidate(
                    observation.id(), observation.sessionId(), idea, parsed.warnings(), existing != null, existing));
        }

        int created = 0;
        int skipped = 0;
        // Capture oldest first so the newest-first Ideas view keeps the observations' order.
        for (int i = candidates.size() - 1; i >= 0; i--) {
            IdeaMigrationResult.Candidate candidate = candidates.get(i);
            if (candidate.alreadyMigrated() || candidate.idea().title() == null) {
                skipped++;
                continue;
            }
            if (!apply) {
                continue;
            }
            IngestResponse response = captureOperations.captureIdea(candidate.idea(), candidate.observationId());
            candidates.set(
                    i,
                    new IdeaMigrationResult.Candidate(
                            candidate.observationId(),
                            candidate.sessionId(),
                            candidate.idea(),
                            candidate.warnings(),
                            false,
                            response.eventId()));
            created++;
        }

        return new IdeaMigrationResult(apply, List.copyOf(candidates), created, skipped);
    }

    private List<IdeaView> collapsed() {
        Map<String, List<TypedEvent>> byKey = new LinkedHashMap<>();
        for (TypedEvent row : newestFirst(reader.eventsOfType(Ideas.EVENT_TYPE, null, SCAN_LIMIT))) {
            byKey.computeIfAbsent(ideaKey(row), key -> new ArrayList<>()).add(row);
        }
        List<IdeaView> views = new ArrayList<>(byKey.size());
        byKey.forEach((key, rows) -> views.add(toView(key, rows)));

        return views;
    }

    /**
     * Canonical timestamps are ISO strings with variable fractional precision, so SQL string order
     * can misplace events captured within the same second; order by the parsed instant instead.
     */
    private static List<TypedEvent> newestFirst(List<TypedEvent> rows) {

        return rows.stream()
                .filter(row -> row != null && row.event() != null && row.event().observedAt() != null)
                .sorted(Comparator.comparing((TypedEvent row) -> row.event().observedAt())
                        .thenComparing(row -> nullToEmpty(row.event().id()))
                        .reversed())
                .toList();
    }

    private Map<String, String> migratedObservationIds() {
        Map<String, String> migrated = new HashMap<>();
        for (TypedEvent row : reader.eventsOfType(Ideas.EVENT_TYPE, null, SCAN_LIMIT)) {
            if (row.event() == null) {
                continue;
            }
            String from = str(metadata(row.event()).get("migratedFrom"));
            if (notBlank(from)) {
                migrated.putIfAbsent(from, row.event().id());
            }
        }

        return migrated;
    }

    /** Rows are newest first: the first row is the latest state, the last is the first capture. */
    private static IdeaView toView(String key, List<TypedEvent> rows) {
        TypedEvent latestRow = rows.getFirst();
        AgentEvent latest = latestRow.event();
        Map<String, Object> meta = metadata(latest);
        Instant firstCapturedAt = rows.getLast().event().observedAt();
        String title = firstNonBlank(str(meta.get("title")), titleFromText(latest.text()));
        String status = Ideas.normalizeStatus(str(meta.get("status")));

        return new IdeaView(
                latest.id(),
                latest.sessionId(),
                latest.source(),
                latest.clientSessionId(),
                firstNonBlank(str(meta.get("repo")), latestRow.cwd()),
                title,
                str(meta.get("oneLiner")),
                Ideas.normalizeOrigin(str(meta.get("origin"))),
                str(meta.get("quote")),
                str(meta.get("sourceRef")),
                asInteger(meta.get("legs")),
                status == null ? Ideas.STATUS_UNTOUCHED : status,
                asStringList(meta.get("connects")),
                str(meta.get("resumeStep")),
                str(meta.get("link")),
                str(meta.get("notes")),
                key,
                latest.observedAt(),
                firstCapturedAt,
                rows.size(),
                str(meta.get("migratedFrom")));
    }

    /** Stored key first; ideas written without one (for example generic event ingest) derive it. */
    private static String ideaKey(TypedEvent row) {
        Map<String, Object> meta = metadata(row.event());
        String stored = str(meta.get("ideaKey"));
        if (notBlank(stored)) {

            return stored.strip();
        }
        String repo = firstNonBlank(str(meta.get("repo")), row.cwd());
        String title =
                firstNonBlank(str(meta.get("title")), titleFromText(row.event().text()));

        return Ideas.defaultKey(repo, title == null ? row.event().id() : title);
    }

    private static String titleFromText(String text) {
        String line = Titles.firstLine(text);
        if (line == null) {

            return null;
        }
        String stripped = line.strip();
        if (stripped.startsWith(Ideas.TEXT_PREFIX)) {
            stripped = stripped.substring(Ideas.TEXT_PREFIX.length()).strip();
        }
        int dash = stripped.indexOf(" — ");
        if (dash > 0) {
            stripped = stripped.substring(0, dash).strip();
        }

        return stripped.isEmpty() ? null : stripped;
    }

    private Set<String> resolveScopes(String project, String repo) {
        Set<String> scopes = null;
        for (String scope : new String[] {project, repo}) {
            if (!notBlank(scope)) {
                continue;
            }
            if (scopes == null) {
                scopes = new LinkedHashSet<>();
            }
            scopes.add(canonicalProject(scope));
            for (String resolved : projectScopes.scopesFor(scope.strip())) {
                scopes.add(canonicalProject(resolved));
            }
        }

        return scopes;
    }

    private static boolean inScope(IdeaView idea, Set<String> scopes) {

        return (notBlank(idea.repo()) && scopes.contains(canonicalProject(idea.repo())));
    }

    private static boolean matchesTerms(IdeaView idea, List<String> terms) {
        String haystack = String.join(
                        "\n",
                        nullToEmpty(idea.title()),
                        nullToEmpty(idea.oneLiner()),
                        nullToEmpty(idea.quote()),
                        nullToEmpty(idea.notes()),
                        nullToEmpty(idea.resumeStep()),
                        nullToEmpty(idea.sourceRef()),
                        nullToEmpty(idea.ideaKey()),
                        nullToEmpty(idea.repo()),
                        nullToEmpty(idea.link()),
                        idea.connects() == null ? "" : String.join("\n", idea.connects()))
                .toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!haystack.contains(term)) {

                return false;
            }
        }

        return true;
    }

    private static Set<String> normalizedStatuses(List<String> statuses) {
        Set<String> out = new HashSet<>();
        if (statuses == null) {

            return out;
        }
        for (String raw : statuses) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                String status = Ideas.normalizeStatus(part);
                if (status == null) {
                    throw new IllegalArgumentException("status '" + part.strip() + "' is not allowed; use one of: "
                            + String.join(", ", Ideas.STATUSES) + ".");
                }
                out.add(status);
            }
        }

        return out;
    }

    private static String normalizedOrigin(String origin) {
        if (!notBlank(origin)) {

            return null;
        }
        String normalized = Ideas.normalizeOrigin(origin);
        if (normalized == null) {
            throw new IllegalArgumentException("origin '" + origin.strip() + "' is not allowed; use one of: "
                    + String.join(", ", Ideas.ORIGINS) + ".");
        }

        return normalized;
    }

    private static List<String> terms(String q) {
        if (!notBlank(q)) {

            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String term : q.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }

        return terms;
    }

    static int clampLimit(Integer limit) {
        if (limit == null) {

            return DEFAULT_LIMIT;
        }

        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static String canonicalProject(String value) {

        return ProjectKey.of(value).value();
    }

    private static Map<String, Object> metadata(AgentEvent event) {

        return event == null || event.metadata() == null ? Map.of() : event.metadata();
    }

    private static String firstNonBlank(String first, String second) {

        return notBlank(first) ? first : notBlank(second) ? second : null;
    }

    private static boolean notBlank(String value) {

        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {

        return value == null ? "" : value;
    }

    private static String str(Object value) {

        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {

            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {

                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {

                return null;
            }
        }

        return null;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object element : list) {
                if (element != null) {
                    out.add(String.valueOf(element));
                }
            }

            return out.isEmpty() ? null : List.copyOf(out);
        }

        return null;
    }
}
