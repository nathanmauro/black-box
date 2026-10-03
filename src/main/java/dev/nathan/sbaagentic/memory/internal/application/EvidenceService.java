package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader.TypedEvent;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.EvidenceKind;
import dev.nathan.sbaagentic.recording.EvidenceRefs;
import dev.nathan.sbaagentic.recording.Lanes;
import dev.nathan.sbaagentic.recording.ProjectScopeResolver;
import dev.nathan.sbaagentic.recording.Titles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Full keyset scan for evidence lists and links to all revisions of an idea. */
@Service
public class EvidenceService {
    private final IdeaService ideas;
    private final ProjectScopeResolver scopes;

    public EvidenceService(IdeaService ideas, ProjectScopeResolver scopes) {
        this.ideas = ideas;
        this.scopes = scopes;
    }

    public EvidenceListResponse list(EvidenceListQuery query) {
        EvidenceListQuery q = query == null ? new EvidenceListQuery(null, null, null, null, null) : query;
        String target =
                q.target() == null || q.target().isBlank() ? null : EvidenceRefs.normalize(q.target(), "target");
        Set<String> projects = resolve(q.project(), q.repo());
        List<String> terms = terms(q.q());
        int limit = IdeaService.clampLimit(q.limit());
        List<EvidenceView> result = new ArrayList<>();
        for (EvidenceRow row : all()) {
            EvidenceView view = row.view();
            if (target != null && !links(view, target)) {
                continue;
            }
            if (projects != null
                    && (view.repo() == null
                            || !projects.contains(ProjectKey.of(view.repo()).value()))) {
                continue;
            }
            if (!terms.isEmpty() && !matches(row, terms)) {
                continue;
            }
            result.add(view);
            if (result.size() == limit) {
                break;
            }
        }

        return new EvidenceListResponse(List.copyOf(result), result.size());
    }

    /** MCP entry point: an unknown key is an actionable tool error. */
    public IdeaDetail detail(String ideaKey) {
        IdeaDetail detail = detailOrNull(ideaKey);
        if (detail == null) {
            throw new IllegalArgumentException("Idea not found; list ideas or check the ideaKey.");
        }

        return detail;
    }

    /** REST entry point: the controller maps an unknown key to its existing 404 envelope. */
    public IdeaDetail detailOrNull(String ideaKey) {
        if (ideaKey == null || ideaKey.isBlank()) {
            throw new IllegalArgumentException("ideaKey is required.");
        }
        String key = ideaKey.strip();
        IdeaService.IdeaRevisions revisions = ideas.revisions(key);
        if (revisions == null) {

            return null;
        }
        Set<String> eventIds = Set.copyOf(revisions.eventIds());
        List<EvidenceView> supports = new ArrayList<>();
        List<EvidenceView> refutes = new ArrayList<>();
        String keyRef = "idea:" + key;
        for (EvidenceRow row : all()) {
            EvidenceView view = row.view();
            if (matchesLink(view.supports(), keyRef, eventIds)) {
                supports.add(view);
            }
            if (matchesLink(view.refutes(), keyRef, eventIds)) {
                refutes.add(view);
            }
        }

        return new IdeaDetail(revisions.idea(), List.copyOf(supports), List.copyOf(refutes));
    }

    private static boolean matchesLink(List<String> links, String key, Set<String> revisions) {
        if (links == null) {

            return false;
        }
        for (String ref : links) {
            if (ref.equals(key)) {

                return true;
            }
            if (ref.startsWith("event:")) {
                String prefix = ref.substring(6);
                if (prefix.length() >= 8 && revisions.stream().anyMatch(id -> id.startsWith(prefix))) {

                    return true;
                }
            }
        }

        return false;
    }

    private static boolean links(EvidenceView view, String target) {

        return (view.supports() != null && view.supports().contains(target))
                || (view.refutes() != null && view.refutes().contains(target));
    }

    private List<EvidenceRow> all() {

        return ideas.allEventsOfType(EvidenceKind.EVENT_TYPE, null).stream()
                .filter(row -> row != null && row.event() != null && row.event().observedAt() != null)
                .sorted(Comparator.comparing((TypedEvent row) -> row.event().observedAt())
                        .thenComparing(row -> row.event().id())
                        .reversed())
                .map(row -> new EvidenceRow(view(row), row.event().text()))
                .toList();
    }

    private static EvidenceView view(TypedEvent row) {
        AgentEvent event = row.event();
        Map<String, Object> meta = event.metadata() == null ? Map.of() : event.metadata();
        String repo = first(str(meta.get("repo")), row.cwd());
        String project = first(str(meta.get("project")), repo);
        Instant observed = event.observedAt();
        if (meta.get("observedAt") != null) {
            try {
                observed = Instant.parse(str(meta.get("observedAt")));
            } catch (RuntimeException ignored) {
                // Generic events may carry malformed metadata; their event time remains usable.
            }
        }

        return new EvidenceView(
                event.id(),
                event.sessionId(),
                event.source(),
                event.clientSessionId(),
                repo,
                first(str(meta.get("claim")), claimFromText(event.text())),
                str(meta.get("excerpt")),
                str(meta.get("sourceRef")),
                str(meta.get("outputDigest")),
                observed,
                first(str(meta.get("capturedBy")), event.source()),
                strings(meta.get("supports")),
                strings(meta.get("refutes")),
                str(meta.get("notes")),
                project,
                Lanes.withoutHome(project, Lanes.read(meta.get("alsoIn"))),
                event.observedAt());
    }

    private static String claimFromText(String text) {
        String line = Titles.firstLine(text);
        if (line == null) {

            return null;
        }
        String claim = line.strip();
        if (claim.startsWith(EvidenceKind.TEXT_PREFIX)) {
            claim = claim.substring(EvidenceKind.TEXT_PREFIX.length()).strip();
        }

        return claim.isEmpty() ? null : claim;
    }

    private Set<String> resolve(String project, String repo) {
        Set<String> result = null;
        for (String value : new String[] {project, repo}) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (result == null) {
                result = new HashSet<>();
            }
            result.add(ProjectKey.of(value).value());
            for (String alias : scopes.scopesFor(value.strip())) {
                result.add(ProjectKey.of(alias).value());
            }
        }

        return result;
    }

    private static boolean matches(EvidenceRow row, List<String> terms) {
        EvidenceView view = row.view();
        String haystack = String.join(
                        "\n",
                        value(view.claim()),
                        value(view.excerpt()),
                        value(view.sourceRef()),
                        value(view.notes()),
                        value(view.repo()),
                        value(view.project()),
                        value(row.text()))
                .toLowerCase(Locale.ROOT);

        return terms.stream().allMatch(haystack::contains);
    }

    private static List<String> terms(String raw) {

        return raw == null || raw.isBlank()
                ? List.of()
                : List.of(raw.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {

            return null;
        }

        return list.stream().filter(item -> item != null).map(String::valueOf).toList();
    }

    private static String value(String value) {

        return value == null ? "" : value;
    }

    private static String str(Object value) {

        return value == null ? null : String.valueOf(value);
    }

    private static String first(String one, String two) {

        return one != null && !one.isBlank() ? one : two;
    }

    private record EvidenceRow(EvidenceView view, String text) {}
}
