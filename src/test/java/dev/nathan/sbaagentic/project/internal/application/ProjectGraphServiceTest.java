package dev.nathan.sbaagentic.project.internal.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.nathan.sbaagentic.project.ProjectAliasSnapshot;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.project.ProjectScope;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import dev.nathan.sbaagentic.project.ProjectTrajectoryResponse;
import dev.nathan.sbaagentic.project.TrajectoryCapture;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectGraphStore;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectGraphStore.CaptureRow;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectGraphStore.TaskRow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGraphServiceTest {

    private static final String CANONICAL = "/repos/trajectory";

    @Test
    void graphCapsCapturesAndTasksAtTheFeedLimit() {
        StubGraphStore store = new StubGraphStore();
        Instant base = Instant.parse("2026-08-05T12:00:00Z");
        store.captures = IntStream.range(0, 130)
                .mapToObj(index -> event(
                        "event-" + index,
                        "Observation",
                        Map.of("kind", "observation"),
                        base.plusSeconds(index)))
                .toList();
        store.tasks = IntStream.range(0, 130)
                .mapToObj(index -> new TaskRow(
                        "task-" + index,
                        "Task " + index,
                        "open",
                        index,
                        base.plusSeconds(index)))
                .toList();
        store.totalCaptures = 130;

        ProjectTrajectoryResponse response = service(store).graph(ProjectKey.of(CANONICAL).encoded());

        assertThat(store.captureLimit).isEqualTo(120);
        assertThat(store.taskLimit).isEqualTo(120);
        assertThat(response.totalCaptures()).isEqualTo(130);
        assertThat(response.captures()).hasSize(120);
        assertThat(response.captures().getFirst().id()).isEqualTo("event-129");
        assertThat(response.captures().getLast().id()).isEqualTo("event-10");
        assertThat(response.tasks()).hasSize(120);
    }

    @Test
    void graphMapsStructuredCaptureMetadataIntoWireKinds() {
        StubGraphStore store = new StubGraphStore();
        store.captures = List.of(
                event("decision", "AgentThought", Map.of(
                        "kind", "decision",
                        "decision", "Keep the backend thin",
                        "rationale", "The frontend owns graph semantics.",
                        "alternatives", List.of("Assemble graph server-side"),
                        "confidence", 0.91,
                        "openLoops", List.of("Add frontend view")),
                        Instant.parse("2026-08-05T12:00:00Z")),
                event("handoff", "AgentThought", Map.of(
                        "kind", "handoff",
                        "contextSummary", "Feed is ready for the UI.",
                        "toAgent", "frontend",
                        "openLoops", List.of("Render futures"),
                        "nextAction", "Build TrajectoryView"),
                        Instant.parse("2026-08-05T12:01:00Z")),
                event("observation", "AgentThought", Map.of("kind", "observation"),
                        Instant.parse("2026-08-05T12:02:00Z")),
                event("projection", "AgentThought", Map.of(
                        "kind", "projection",
                        "paths", List.of(Map.of(
                                "title", "Ship graph tab",
                                "description", "Wire the Project page tab switcher.",
                                "confidence", 0.64))),
                        Instant.parse("2026-08-05T12:03:00Z")),
                event("event-type-handoff", "Handoff", Map.of(
                        "contextSummary", "Event type alone marks this as a handoff."),
                        Instant.parse("2026-08-05T12:04:00Z")),
                event("unknown-event", "AssistantMessage", Map.of(),
                        Instant.parse("2026-08-05T12:05:00Z")));

        Map<String, TrajectoryCapture> captures = service(store).graph(ProjectKey.of(CANONICAL).encoded())
                .captures()
                .stream()
                .collect(Collectors.toMap(TrajectoryCapture::id, capture -> capture));

        TrajectoryCapture decision = captures.get("decision");
        assertThat(decision.kind()).isEqualTo("decision");
        assertThat(decision.headline()).isEqualTo("Keep the backend thin");
        assertThat(decision.rationale()).isEqualTo("The frontend owns graph semantics.");
        assertThat(decision.alternatives()).containsExactly("Assemble graph server-side");
        assertThat(decision.openLoops()).containsExactly("Add frontend view");
        assertThat(decision.confidence()).isEqualTo(0.91);

        TrajectoryCapture handoff = captures.get("handoff");
        assertThat(handoff.kind()).isEqualTo("handoff");
        assertThat(handoff.headline()).isEqualTo("Feed is ready for the UI.");
        assertThat(handoff.toAgent()).isEqualTo("frontend");
        assertThat(handoff.nextAction()).isEqualTo("Build TrajectoryView");

        assertThat(captures.get("observation").kind()).isEqualTo("observation");

        assertThat(captures.get("event-type-handoff").kind()).isEqualTo("handoff");
        assertThat(captures.get("event-type-handoff").headline())
                .isEqualTo("Event type alone marks this as a handoff.");

        assertThat(captures.get("unknown-event").kind()).isEqualTo("observation");

        TrajectoryCapture projection = captures.get("projection");
        assertThat(projection.kind()).isEqualTo("projection");
        assertThat(projection.headline()).isEqualTo("Ship graph tab");
        assertThat(projection.paths()).singleElement().satisfies(path -> {
            assertThat(path.title()).isEqualTo("Ship graph tab");
            assertThat(path.description()).isEqualTo("Wire the Project page tab switcher.");
            assertThat(path.confidence()).isEqualTo(0.64);
        });
    }

    @Test
    void graphKeepsMeldsInNewestFirstCaptureOrder() {
        StubGraphStore store = new StubGraphStore();
        store.captures = List.of(
                event("older", "Decision", Map.of("kind", "decision"),
                        Instant.parse("2026-08-05T12:00:00Z")),
                new CaptureRow(
                        "meld-1",
                        "saved_meld",
                        "SavedMeld",
                        null,
                        null,
                        null,
                        "meld",
                        "Saved synthesis",
                        "The durable synthesis body.",
                        Map.of(),
                        Instant.parse("2026-08-05T12:01:00Z")),
                event("newer", "Handoff", Map.of("kind", "handoff"),
                        Instant.parse("2026-08-05T12:02:00Z")));

        ProjectTrajectoryResponse response = service(store).graph(ProjectKey.of(CANONICAL).encoded());

        assertThat(response.captures()).extracting(TrajectoryCapture::id)
                .containsExactly("newer", "meld-1", "older");
        assertThat(response.captures().get(1).kind()).isEqualTo("meld");
        assertThat(response.captures().get(1).headline()).isEqualTo("Saved synthesis");
    }

    @Test
    void graphReturnsAnEmptyFeedForProjectsWithoutFacts() {
        StubGraphStore store = new StubGraphStore();

        ProjectTrajectoryResponse response = service(store).graph(ProjectKey.of(CANONICAL).encoded());

        assertThat(response.projectKey()).isEqualTo(ProjectKey.of(CANONICAL).encoded());
        assertThat(response.canonicalKey()).isEqualTo(CANONICAL);
        assertThat(response.label()).isEqualTo(CANONICAL);
        assertThat(response.generatedAt()).isNotNull();
        assertThat(response.totalCaptures()).isZero();
        assertThat(response.captures()).isEmpty();
        assertThat(response.tasks()).isEmpty();
    }

    private static ProjectGraphService service(StubGraphStore store) {
        return new ProjectGraphService(store, new AliasStub());
    }

    private static CaptureRow event(
            String id,
            String eventType,
            Map<String, Object> metadata,
            Instant observedAt) {
        return new CaptureRow(
                id,
                "raw_event",
                eventType,
                "session-" + id,
                "Session " + id,
                "client-" + id,
                "codex",
                null,
                id + " text",
                metadata,
                observedAt);
    }

    private static final class StubGraphStore implements ProjectGraphStore {
        private List<CaptureRow> captures = new ArrayList<>();
        private List<TaskRow> tasks = new ArrayList<>();
        private long totalCaptures;
        private int captureLimit;
        private int taskLimit;

        @Override
        public List<CaptureRow> recentCaptures(String canonicalKey, int limit) {
            assertThat(canonicalKey).isEqualTo(CANONICAL);
            captureLimit = limit;
            return captures;
        }

        @Override
        public List<TaskRow> openTasks(String canonicalKey, int limit) {
            assertThat(canonicalKey).isEqualTo(CANONICAL);
            taskLimit = limit;
            return tasks;
        }

        @Override
        public long totalCaptures(String canonicalKey) {
            assertThat(canonicalKey).isEqualTo(CANONICAL);
            return totalCaptures;
        }
    }

    private static final class AliasStub implements ProjectScopeOperations {

        @Override
        public String resolve(String scope) {
            assertThat(scope).isEqualTo(CANONICAL);
            return CANONICAL;
        }

        @Override
        public List<String> scopesFor(String scope) {
            return List.of(scope);
        }

        @Override
        public List<ProjectScope> projectScopesFor(String scope) {
            return List.of();
        }

        @Override
        public ProjectAliasSnapshot snapshot() {
            throw new UnsupportedOperationException("not used");
        }
    }
}
