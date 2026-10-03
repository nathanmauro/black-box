package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.CompactPageRequest;
import dev.nathan.sbaagentic.memory.CompactSearchOperations;
import dev.nathan.sbaagentic.memory.CompactSearchPage;
import dev.nathan.sbaagentic.memory.CompactSearchResult;
import dev.nathan.sbaagentic.memory.CompactSearchResult.*;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader.Candidate;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader.PageQuery;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader.PageRow;
import dev.nathan.sbaagentic.memory.internal.application.port.SearchIndex;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import dev.nathan.sbaagentic.query.CompactQueryDiagnostics;
import dev.nathan.sbaagentic.query.EventQuery;
import dev.nathan.sbaagentic.query.SqlInstant;
import dev.nathan.sbaagentic.recording.EventTypes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CompactSearchService implements CompactSearchOperations {
    private static final int CANDIDATE_LIMIT = 200;
    private static final int MAX_TERMS = 16;
    private static final int MAX_TERM_CODE_POINTS = 512;
    private static final int MAX_TERM_BYTES = 2048;
    private static final int MAX_PROJECT_CODE_POINTS = 1024;
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern VOICE = Pattern.compile("^codex-voice:[^:]{1,64}:(" + UUID + "):" + UUID + "$");
    private final CompactEventReader events;
    private final SearchIndex index;
    private final ProjectScopeOperations projects;
    private final Clock clock;

    public CompactSearchService(
            CompactEventReader events, SearchIndex index, ProjectScopeOperations projects, Clock clock) {
        this.events = events;
        this.index = index;
        this.projects = projects;
        this.clock = clock;
    }

    @Override
    public CompactSearchResult search(
            String query, Integer limit, Integer maxBytes, String excludeSession, Boolean groupSimilar) {
        int budget = maxBytes == null ? 24_000 : maxBytes;
        if (budget < 2048 || budget > 64_000)

            return error("invalid_request", 2048, "maxBytes must be between 2048 and 64000.");
        if (query == null || query.isBlank() || query.length() > 1024) {

            return error("invalid_query", budget, "Supply a nonblank query of at most 1024 UTF-16 code units.");
        }
        if (excludeSession != null && (excludeSession.isBlank() || excludeSession.length() > 256)) {

            return error(
                    "invalid_request",
                    budget,
                    "excludeSession must be a nonblank exact identity of at most 256 characters.");
        }
        var diagnostics = CompactQueryDiagnostics.validate(query);
        if (!diagnostics.isEmpty())

            return error("invalid_query", budget, diagnostics.get(0));

        EventQuery parsed = EventQuery.parse(query);
        Clock requestClock = Clock.fixed(clock.instant(), clock.getZone());
        Map<String, Object> filters = filters(parsed, requestClock, excludeSession);
        Map<String, Coverage> coverage = new LinkedHashMap<>();
        coverage.put("local", new Coverage("searched", 0, false));
        coverage.put("elastic", new Coverage("skipped_filters", 0, false));
        if (bytes(result(query, List.of(), filters, coverage, 0, budget, List.of())) > budget) {

            return error(
                    "invalid_request",
                    budget,
                    "Query/filter metadata exceeds maxBytes; shorten the query or increase the budget.");
        }
        int countLimit = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
        List<String> scopes = parsed.projectGroups().stream()
                .flatMap(group -> projects.scopesFor(group).stream())
                .distinct()
                .toList();
        List<Candidate> local = events.searchCompact(parsed, scopes, CANDIDATE_LIMIT, requestClock, excludeSession);
        var elastic = parsed.hasAnyFacet() || excludeSession != null
                ? new SearchIndex.CompactResults("skipped_filters", List.of())
                : index.searchCompact(query, CANDIDATE_LIMIT);
        coverage.put("local", new Coverage("searched", local.size(), local.size() >= CANDIDATE_LIMIT));
        coverage.put(
                "elastic",
                new Coverage(
                        elastic.status(),
                        elastic.items().size(),
                        elastic.items().size() >= CANDIDATE_LIMIT));

        Map<String, Candidate> canonical = new LinkedHashMap<>();
        for (Candidate item : local) if (identity(item.eventId()) != null) canonical.put(item.eventId(), item);
        var indexedIds = elastic.items().stream()
                .map(Candidate::eventId)
                .map(CompactSearchService::identity)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<Hit> ordered = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < Math.max(local.size(), elastic.items().size()); i++) {
            if (i < local.size()) {
                Candidate row = local.get(i);
                if (identity(row.eventId()) == null || seen.add(row.eventId()))
                    ordered.add(hit(
                            row,
                            true,
                            indexedIds.contains(row.eventId()) ? List.of("local", "elastic") : List.of("local")));
            }
            if (i < elastic.items().size()) {
                Candidate row = elastic.items().get(i);
                // Indexed copies are not independently verified canonical sources.
                if (!canonical.containsKey(row.eventId())
                        && (identity(row.eventId()) == null || seen.add(row.eventId()))) {
                    ordered.add(hit(row, false, List.of("elastic")));
                }
            }
        }
        if (!Boolean.FALSE.equals(groupSimilar)) ordered = groupObservers(ordered);
        int candidates = ordered.size();
        List<Hit> kept = new ArrayList<>(ordered.subList(0, Math.min(countLimit, candidates)));
        List<String> notes = List.of(
                "Excerpts can omit matching metadata. Capture type and text similarity do not prove origin or causation.");
        CompactSearchResult result = result(query, kept, filters, coverage, candidates - kept.size(), budget, notes);
        while (bytes(result) > budget && !kept.isEmpty()) {
            if (kept.size() == 1 && kept.getFirst().excerpt() != null) {
                Hit first = kept.getFirst();
                int low = 0,
                        high = first.excerpt().codePointCount(0, first.excerpt().length());
                Hit best = withExcerpt(first, "");
                while (low <= high) {
                    int middle = (low + high) / 2;
                    Hit candidate = withExcerpt(first, clip(first.excerpt(), middle));
                    if (bytes(result(query, List.of(candidate), filters, coverage, candidates - 1, budget, notes))
                            <= budget) {
                        best = candidate;
                        low = middle + 1;
                    } else high = middle - 1;
                }
                kept.set(0, best);
                result = result(query, kept, filters, coverage, candidates - 1, budget, notes);
                if (bytes(result) <= budget) break;
            }
            kept.removeLast();
            result = result(query, kept, filters, coverage, candidates - kept.size(), budget, notes);
        }

        return bytes(result) <= budget
                ? result
                : error(
                        "invalid_request",
                        budget,
                        "Query/filter metadata exceeds maxBytes; shorten the query or increase the budget.");
    }

    @Override
    public CompactSearchPage searchPage(CompactPageRequest request) {
        int budget = request.maxBytes() == null ? 24_000 : request.maxBytes();
        int limit = request.limit() == null ? 10 : request.limit();
        if (budget < 2048 || budget > 64_000)

            return pageError("invalid_request", 2048, limit, "maxBytes must be between 2048 and 64000.");
        if (limit < 1 || limit > 50)

            return pageError("invalid_request", budget, 10, "limit must be between 1 and 50.");

        List<String> rawTerms = request.terms() == null ? List.of() : request.terms();
        if (rawTerms.isEmpty() || rawTerms.size() > MAX_TERMS)

            return pageError("invalid_request", budget, limit, "Supply 1 to " + MAX_TERMS + " term values.");
        int termBytes = 0;
        for (String term : rawTerms) {
            if (term == null
                    || term.isEmpty()
                    || !CompactPageCursor.wellFormed(term)
                    || term.codePointCount(0, term.length()) > MAX_TERM_CODE_POINTS
                    || term.chars().anyMatch(c -> Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r'))

                return pageError(
                        "invalid_request",
                        budget,
                        limit,
                        "Each term must be 1-" + MAX_TERM_CODE_POINTS
                                + " Unicode code points without control characters other than tab, LF or CR.");
            termBytes += term.getBytes(StandardCharsets.UTF_8).length;
        }
        if (termBytes > MAX_TERM_BYTES)

            return pageError(
                    "invalid_request", budget, limit, "Terms may total at most " + MAX_TERM_BYTES + " UTF-8 bytes.");
        List<String> terms = List.copyOf(new TreeSet<>(rawTerms));
        String project = null;
        if (request.projectExact() != null) {
            String raw = request.projectExact();
            if (raw.isBlank()
                    || !CompactPageCursor.wellFormed(raw)
                    || raw.codePointCount(0, raw.length()) > MAX_PROJECT_CODE_POINTS
                    || raw.chars().anyMatch(Character::isISOControl))

                return pageError(
                        "invalid_request",
                        budget,
                        limit,
                        "projectExact must be a nonblank path of at most " + MAX_PROJECT_CODE_POINTS
                                + " code points without control characters; use __no_project__ for none.");
            project = ProjectKey.of(raw).value();
        }
        String session = request.sessionId();
        if (session != null
                && (session.isEmpty()
                        || session.length() > 256
                        || !CompactPageCursor.wellFormed(session)
                        || session.chars().anyMatch(Character::isISOControl)))

            return pageError(
                    "invalid_request",
                    budget,
                    limit,
                    "sessionId must be an exact internal session ID of 1-256 characters without control characters.");
        Instant until = null;
        if (request.until() != null) {
            try {
                if (request.until().length() > 64) throw new DateTimeParseException("too long", request.until(), 0);
                until = Instant.parse(request.until());
            } catch (DateTimeParseException ex) {

                return pageError(
                        "invalid_request",
                        budget,
                        limit,
                        "until must be an ISO-8601 instant such as 2026-08-18T00:00:00.000000001Z.");
            }
        }
        CompactPageCursor.Position position = null;
        if (request.before() != null) {
            try {
                position = CompactPageCursor.decode(request.before());
            } catch (IllegalArgumentException ex) {

                return pageError("invalid_cursor", budget, limit, ex.getMessage());
            }
            if (until != null && !until.equals(position.until()))

                return pageError(
                        "cursor_mismatch", budget, limit, "until differs from the cutoff carried by the cursor.");
            until = position.until();
        }
        if (until == null) until = clock.instant();
        String fingerprint = CompactPageCursor.fingerprint(terms, project, session, until);
        if (position != null && !position.fingerprint().equals(fingerprint))

            return pageError(
                    "cursor_mismatch",
                    budget,
                    limit,
                    "The cursor belongs to different terms, projectExact or sessionId; restart without before.");
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("terms", terms);
        if (project != null) filters.put("projectExact", project);
        if (session != null) filters.put("sessionId", session);
        filters.put("until", until.toString());
        PageFit empty = new PageFit(List.of(), List.of(), false, filters, fingerprint, until, limit, budget);
        if (bytes(empty.page(List.of(), false)) > budget)

            return pageError(
                    "invalid_request",
                    budget,
                    limit,
                    "Filter metadata exceeds maxBytes; shorten the terms or increase the budget.");

        List<PageRow> rows = events.pageCompact(
                new PageQuery(
                        terms,
                        project,
                        session,
                        SqlInstant.key(until),
                        position == null ? null : position.orderKey(),
                        position == null ? null : position.eventId()),
                limit + 1);
        List<PageRow> delivered = rows.subList(0, Math.min(limit, rows.size()));
        PageFit fit = new PageFit(
                delivered,
                delivered.stream()
                        .map(row -> hit(row.candidate(), true, List.of("local")))
                        .toList(),
                rows.size() > limit,
                filters,
                fingerprint,
                until,
                limit,
                budget);
        try {

            return fit.fitted();
        } catch (IllegalStateException ex) {

            return pageError("cursor_unavailable", budget, limit, ex.getMessage());
        }
    }

    /** Fits one page to the byte budget, advancing only from the last hit that is actually delivered. */
    private record PageFit(
            List<PageRow> rows,
            List<Hit> hits,
            boolean moreRows,
            Map<String, Object> filters,
            String fingerprint,
            Instant until,
            int limit,
            int budget) {

        CompactSearchPage fitted() {
            List<Hit> kept = new ArrayList<>(hits);
            CompactSearchPage page = page(kept, false);
            while (bytes(page) > budget && kept.size() > 1) {
                kept.removeLast();
                page = page(kept, true);
            }
            if (bytes(page) > budget && kept.size() == 1 && kept.getFirst().excerpt() != null) {
                Hit first = kept.getFirst();
                int low = 0,
                        high = first.excerpt().codePointCount(0, first.excerpt().length());
                Hit best = withExcerpt(first, "");
                while (low <= high) {
                    int middle = (low + high) / 2;
                    Hit candidate = withExcerpt(first, clip(first.excerpt(), middle));
                    if (bytes(page(List.of(candidate), true)) <= budget) {
                        best = candidate;
                        low = middle + 1;
                    } else high = middle - 1;
                }
                kept.set(0, best);
                page = page(kept, true);
            }

            return bytes(page) <= budget
                    ? page
                    : pageError(
                            "budget_exceeded",
                            budget,
                            limit,
                            "The next hit cannot fit maxBytes even with an empty excerpt; increase maxBytes. No cursor advanced.");
        }

        CompactSearchPage page(List<Hit> kept, boolean limited) {
            boolean hasMore = moreRows || kept.size() < hits.size();
            String next = null;
            if (hasMore && !kept.isEmpty()) {
                PageRow last = rows.get(kept.size() - 1);
                next = CompactPageCursor.encode(
                        new CompactPageCursor.Position(fingerprint, until, last.orderKey(), last.eventId()));
            }

            return new CompactSearchPage(
                    "ok",
                    "canonical",
                    kept.size(),
                    List.copyOf(kept),
                    filters,
                    hasMore,
                    next,
                    limited,
                    kept.stream().anyMatch(Hit::excerptTruncated),
                    limit,
                    budget,
                    List.of(
                            "Literal case-sensitive matches over text, tool name and stored metadata JSON; excerpts can omit the match."));
        }
    }

    private static CompactSearchPage pageError(String status, int budget, int limit, String diagnostic) {

        return new CompactSearchPage(
                status,
                "canonical",
                0,
                List.of(),
                Map.of(),
                false,
                null,
                false,
                false,
                limit,
                budget,
                List.of(diagnostic));
    }

    private static Map<String, Object> filters(EventQuery parsed, Clock clock, String exclude) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asOf", clock.instant().toString());
        result.put("timezone", clock.getZone().getId());
        for (EventQuery.Field field : EventQuery.Field.values()) {
            if (!parsed.values(field).isEmpty())
                result.put(field.name().toLowerCase(Locale.ROOT), parsed.values(field));
            if (!parsed.excluded(field).isEmpty())
                result.put("exclude_" + field.name().toLowerCase(Locale.ROOT), parsed.excluded(field));
        }
        parsed.sessionRef().ifPresent(value -> result.put("session", value));
        parsed.sinceSpec()
                .ifPresent(
                        spec -> result.put("sinceInclusive", spec.resolve(clock).toString()));
        parsed.untilSpec()
                .ifPresent(spec -> result.put(
                        spec.exclusiveEnd() ? "untilExclusive" : "untilInclusive",
                        spec.resolve(clock).toString()));
        if (parsed.includeAll()) result.put("isAll", true);
        if (exclude != null) result.put("excludeSession", exclude);

        return result;
    }

    private static CompactSearchResult result(
            String query,
            List<Hit> items,
            Map<String, Object> filters,
            Map<String, Coverage> coverage,
            int omitted,
            int budget,
            List<String> diagnostics) {
        boolean truncated = omitted > 0 || items.stream().anyMatch(Hit::excerptTruncated);

        return new CompactSearchResult(
                "ok",
                query,
                items.size(),
                List.copyOf(items),
                filters,
                coverage,
                truncated,
                omitted,
                budget,
                diagnostics);
    }

    private static CompactSearchResult error(String status, int budget, String diagnostic) {

        return new CompactSearchResult(
                status, null, 0, List.of(), Map.of(), Map.of(), false, 0, budget, List.of(diagnostic));
    }

    private static int bytes(Object result) {

        return CompactSearchJson.write(result).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Hit hit(Candidate row, boolean local, List<String> backends) {
        String id = identity(row.eventId()),
                session = identity(row.sessionId()),
                client = identity(row.clientSessionId());
        String source = identity(row.source()), kind = identity(row.eventType()), role = identity(row.role());
        boolean cut = !local
                || row.text() != null && row.text().codePointCount(0, row.text().length()) > 600;
        String excerpt = clip(row.text(), 600);
        String provenance = !local
                ? "unverified_index_copy"
                : java.util.Set.of("pretooluse", "posttooluse", "toolcall", "toolresult", "tooluse")
                                .contains(EventTypes.normalize(kind))
                        ? "observer_output"
                        : "user".equals(role)
                                ? "recorded_user_message"
                                : "Handoff".equals(kind)
                                        ? "captured_summary"
                                        : "assistant".equals(role) ? "recorded_assistant_content" : "unknown";
        String external = null;
        if (local && "codex".equals(source) && client != null) {
            var match = VOICE.matcher(client);
            if (match.matches()) external = match.group(1);
            else if (client.matches(UUID)) external = client;
        }
        SourceReference ref = new SourceReference(
                local && id != null ? "recorded_event" : "unresolved",
                local && id != null ? "/api/events/" + encode(id) : null,
                local && id != null && session != null
                        ? "/?view=browse&session=" + encode(session) + "&event=" + encode(id)
                        : null,
                external != null ? "codex" : null,
                external,
                external == null ? "unavailable" : "candidate_requires_verification");

        return new Hit(
                id,
                session,
                client,
                source,
                kind,
                role,
                identity(row.observedAt()),
                excerpt,
                cut,
                backends,
                provenance,
                ref,
                List.of(),
                1,
                false);
    }

    private static List<Hit> groupObservers(List<Hit> hits) {
        Map<String, List<Hit>> groups = new LinkedHashMap<>();
        List<Hit> result = new ArrayList<>();
        for (Hit hit : hits) {
            if ("observer_output".equals(hit.provenance())
                    && !hit.excerptTruncated()
                    && hit.excerpt() != null
                    && !hit.excerpt().isBlank()
                    && hit.eventId() != null) {
                String key = CompactSearchJson.write(List.of(
                        hit.source() == null ? "" : hit.source(),
                        hit.eventType() == null ? "" : hit.eventType(),
                        hit.excerpt()));
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
            }
        }
        var emitted = new java.util.HashSet<String>();
        Map<String, List<Hit>> byId = new java.util.HashMap<>();
        groups.values().forEach(group -> group.forEach(hit -> byId.put(hit.eventId(), group)));
        for (Hit hit : hits) {
            List<Hit> group = byId.get(hit.eventId());
            if (group == null || group.size() == 1) {
                result.add(hit);
                continue;
            }
            if (!emitted.add(group.getFirst().eventId())) continue;
            result.add(new Hit(
                    hit.eventId(),
                    hit.sessionId(),
                    hit.clientSessionId(),
                    hit.source(),
                    hit.eventType(),
                    hit.role(),
                    hit.observedAt(),
                    hit.excerpt(),
                    hit.excerptTruncated(),
                    hit.backends(),
                    "similar_observer_text_origin_unknown",
                    hit.sourceReference(),
                    group.stream().skip(1).limit(5).map(Hit::eventId).toList(),
                    group.size(),
                    group.size() > 6));
        }

        return result;
    }

    private static String identity(String value) {

        return value == null || value.isBlank() || value.length() > 256 ? null : value;
    }

    private static Hit withExcerpt(Hit hit, String excerpt) {

        return new Hit(
                hit.eventId(),
                hit.sessionId(),
                hit.clientSessionId(),
                hit.source(),
                hit.eventType(),
                hit.role(),
                hit.observedAt(),
                excerpt,
                true,
                hit.backends(),
                hit.provenance(),
                hit.sourceReference(),
                hit.similarEventIds(),
                hit.similarCount(),
                hit.similarMembersTruncated());
    }

    private static String clip(String text, int length) {

        return text == null || text.codePointCount(0, text.length()) <= length
                ? text
                : text.substring(0, text.offsetByCodePoints(0, length));
    }

    private static String encode(String value) {

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
