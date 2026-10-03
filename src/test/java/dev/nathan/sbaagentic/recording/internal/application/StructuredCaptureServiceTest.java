package dev.nathan.sbaagentic.recording.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureEvidenceRequest;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.LaneListing;
import dev.nathan.sbaagentic.recording.ProjectionPath;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StructuredCaptureServiceTest {

    @Mock
    EventRecorder recorder;

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void invalidConfidenceNeverReachesNormalOrReplacementRecorder(double confidence) {
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        for (String supersedes : Arrays.asList(null, "previous-event")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.captureDecision(new CaptureDecisionRequest(
                            "codex", "client", "/repo", "Decision", "Reason", null, confidence, null, supersedes)))
                    .withMessage("confidence must be a finite number between 0.0 and 1.0.");
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureProjection(new CaptureProjectionRequest(
                        "codex",
                        "client",
                        "/repo",
                        null,
                        Arrays.asList(
                                null,
                                new ProjectionPath(" ", "Discarded", Double.NaN),
                                new ProjectionPath("Future", null, confidence)))))
                .withMessage("paths[2].confidence must be a finite number between 0.0 and 1.0.");
        verifyNoInteractions(recorder);
    }

    @Test
    void captureProjectionCapsPathsAndStoresProjectionMetadata() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-1", "session-1", "codex", "client-1", "Projection", false));

        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        service.captureProjection(new CaptureProjectionRequest(
                "codex",
                "client-1",
                "/repo",
                "Head handoff left graph capture open",
                List.of(
                        new ProjectionPath("Path 1", "Description 1", 0.1),
                        new ProjectionPath("Path 2", "Description 2", 0.2),
                        new ProjectionPath("Path 3", "Description 3", 0.3),
                        new ProjectionPath("Path 4", "Description 4", 0.4),
                        new ProjectionPath("Path 5", "Description 5", 0.5),
                        new ProjectionPath("Path 6", "Description 6", 0.6))));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(captor.capture());
        EventIngestRequest captured = captor.getValue();

        assertThat(captured.eventType()).isEqualTo("Projection");
        assertThat(captured.cwd()).isEqualTo("/repo");
        assertThat(captured.text())
                .contains("Projected futures:")
                .contains("1. Path 1 — Description 1")
                .contains("5. Path 5 — Description 5")
                .contains("Basis: Head handoff left graph capture open")
                .doesNotContain("Path 6");

        assertThat(captured.metadata()).containsEntry("kind", "projection");
        assertThat(captured.metadata()).containsEntry("basis", "Head handoff left graph capture open");
        assertThat(captured.metadata()).containsEntry("repo", "/repo");
        assertThat(captured.metadata().get("paths")).isInstanceOf(List.class);

        List<?> paths = (List<?>) captured.metadata().get("paths");
        assertThat(paths).hasSize(5);
        assertThat(paths.getFirst()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) paths.getFirst();
        assertThat(first).containsEntry("title", "Path 1");
        assertThat(first).containsEntry("description", "Description 1");
        assertThat(first).containsEntry("confidence", 0.1);
        assertThat(paths.getLast()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) paths.getLast();
        assertThat(last).containsEntry("title", "Path 5");
    }

    @Test
    void captureProjectionRejectsNullAndEmptyPaths() {
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureProjection(
                        new CaptureProjectionRequest("codex", "client-null", "/repo", "Basis", null)))
                .withMessage("Projection paths must include at least one path with a title.");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureProjection(
                        new CaptureProjectionRequest("codex", "client-empty", "/repo", "Basis", List.of())))
                .withMessage("Projection paths must include at least one path with a title.");

        verifyNoInteractions(recorder);
    }

    @Test
    void captureProjectionTrimsBlankPathEntriesBeforeCapping() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-2", "session-1", "codex", "client-2", "Projection", false));

        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        service.captureProjection(new CaptureProjectionRequest(
                "codex",
                "client-2",
                "/repo",
                "Trim blank entries",
                List.of(
                        new ProjectionPath(" ", " ", null),
                        new ProjectionPath(" ", "Description without a title", 0.99),
                        new ProjectionPath(" Path 1 ", " ", null),
                        new ProjectionPath("Path 2", "Description 2", 0.2),
                        new ProjectionPath("Path 3", "Description 3", 0.3),
                        new ProjectionPath("Path 4", "Description 4", 0.4),
                        new ProjectionPath("Path 5", "Description 5", 0.5),
                        new ProjectionPath("Path 6", "Description 6", 0.6))));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(captor.capture());
        EventIngestRequest captured = captor.getValue();

        assertThat(captured.text())
                .contains("1. Path 1")
                .contains("5. Path 5 — Description 5")
                .doesNotContain("Untitled projection")
                .doesNotContain("Description without a title")
                .doesNotContain("Path 6");

        List<?> paths = (List<?>) captured.metadata().get("paths");
        assertThat(paths).hasSize(5);
        assertThat(paths.getFirst()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) paths.getFirst();
        assertThat(first).containsEntry("title", "Path 1");
        assertThat(first).doesNotContainKey("description");
    }

    @Test
    void captureProjectionWithoutBasisLeavesPathRenderAsHeadlineFallback() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-3", "session-1", "codex", "client-3", "Projection", false));

        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        service.captureProjection(new CaptureProjectionRequest(
                "codex", "client-3", "/repo", null, List.of(new ProjectionPath("First future", null, null))));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(captor.capture());
        EventIngestRequest captured = captor.getValue();

        assertThat(captured.text())
                .startsWith("Projected futures:\n1. First future")
                .doesNotContain("Basis:");
        assertThat(captured.metadata()).doesNotContainKey("basis");
    }

    @Test
    void captureIdeaStoresEveryFieldAndRendersReadableText() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-1", "session-1", "claude", "client-1", "Idea", false));

        new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()))
                .captureIdea(new CaptureIdeaRequest(
                        "claude",
                        "client-1",
                        "/work/sba-agentic/",
                        "  Lanes board ",
                        " One swimlane per project. ",
                        "Nathan-Aside",
                        "a live board with lanes",
                        "session-9",
                        8,
                        "Partially_Built",
                        List.of(" Orbit (NAT-196) ", " ", "project identity"),
                        "sketch the lanes",
                        "obsidian://open?vault=obsidian&file=Ideas%2Flanes",
                        "Prior art: the idea sky.",
                        null,
                        " Board ",
                        List.of(new LaneListing(" Other ", 0.8))));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(captor.capture());
        EventIngestRequest captured = captor.getValue();

        assertThat(captured.eventType()).isEqualTo("Idea");
        assertThat(captured.cwd()).isEqualTo("/work/sba-agentic/");
        assertThat(captured.metadata())
                .containsEntry("kind", "idea")
                .containsEntry("title", "Lanes board")
                .containsEntry("oneLiner", "One swimlane per project.")
                .containsEntry("origin", "human-aside")
                .containsEntry("status", "partially-built")
                .containsEntry("legs", 8)
                .containsEntry("quote", "a live board with lanes")
                .containsEntry("sourceRef", "session-9")
                .containsEntry("connects", List.of("Orbit (NAT-196)", "project identity"))
                .containsEntry("resumeStep", "sketch the lanes")
                .containsEntry("link", "obsidian://open?vault=obsidian&file=Ideas%2Flanes")
                .containsEntry("notes", "Prior art: the idea sky.")
                .containsEntry("ideaKey", "sba-agentic-lanes-board")
                .containsEntry("repo", "/work/sba-agentic/")
                .containsEntry("project", "Board")
                .containsEntry("alsoIn", List.of(Map.of("project", "Other", "score", 0.8)))
                .doesNotContainKey("migratedFrom");
        assertThat(captured.text())
                .startsWith("[Idea] Lanes board — One swimlane per project.\n")
                .contains("Origin: human-aside")
                .contains("Status: partially-built")
                .contains("Legs: 8/10")
                .contains("Quote: \"a live board with lanes\"")
                .contains("Source: session-9")
                .contains("Connects: Orbit (NAT-196); project identity")
                .contains("Resume: sketch the lanes")
                .contains("Idea key: sba-agentic-lanes-board")
                .contains("Project: Board")
                .contains("Also in: Other (0.8)")
                .endsWith("Notes: Prior art: the idea sky.");
    }

    @Test
    void captureIdeaDefaultsStatusAndKeepsExplicitIdeaKeyAndMigrationSource() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-2", "session-1", "codex", "client-1", "Idea", false));

        new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()))
                .captureIdea(
                        new CaptureIdeaRequest(
                                "codex",
                                "client-1",
                                null,
                                "Evidence kind",
                                "Make Evidence first-class.",
                                "agent-proposed",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                " evidence-kind "),
                        "observation-7");

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(captor.capture());
        Map<String, Object> metadata = captor.getValue().metadata();
        assertThat(metadata)
                .containsEntry("status", "untouched")
                .containsEntry("origin", "agent-proposed")
                .containsEntry("ideaKey", "evidence-kind")
                .containsEntry("migratedFrom", "observation-7")
                .doesNotContainKeys("legs", "quote", "connects", "repo", "notes");
        assertThat(captor.getValue().text())
                .isEqualTo("[Idea] Evidence kind — Make Evidence first-class.\n"
                        + "\nOrigin: agent-proposed\nStatus: untouched\nIdea key: evidence-kind");
    }

    @Test
    void captureIdeaRejectsInvalidFieldsWithActionableMessages() {
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(idea("brainstorm", null, null)))
                .withMessageContaining("origin 'brainstorm' is not allowed")
                .withMessageContaining("human-aside, agent-proposed, joint");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(idea(" ", null, null)))
                .withMessageContaining("origin must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(idea("joint", "done", null)))
                .withMessageContaining("status 'done' is not allowed")
                .withMessageContaining("untouched, partially-built, built-unused, superseded, tracked");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(idea("joint", null, 11)))
                .withMessageContaining("legs must be between 0 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(idea("joint", null, -1)))
                .withMessageContaining("legs must be between 0 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(new CaptureIdeaRequest(
                        "codex",
                        "client-1",
                        null,
                        " ",
                        "one",
                        "joint",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
                .withMessageContaining("title must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(new CaptureIdeaRequest(
                        "codex",
                        "client-1",
                        null,
                        "Title",
                        null,
                        "joint",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
                .withMessageContaining("oneLiner must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureIdea(new CaptureIdeaRequest(
                        " ",
                        "client-1",
                        null,
                        "Title",
                        "One",
                        "joint",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
                .withMessageContaining("source must not be blank");
        verifyNoInteractions(recorder);
    }

    @Test
    void captureIdeaDerivesTheDefaultKeyFromTheRedactedTitle() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-4", "session-1", "codex", "client-1", "Idea", false));
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        String githubToken = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
        List<String> titles = List.of(
                "Rotate AKIAIOSFODNN7EXAMPLE out of CI",
                "Revoke " + githubToken + " today",
                "Stop logging password: hunter2secret",
                "Send Bearer abcdefghijklmnopqrstuvwxyz0123 only once");
        for (String title : titles) {
            service.captureIdea(new CaptureIdeaRequest(
                    "codex",
                    "client-1",
                    "/x/proj",
                    title,
                    "One liner.",
                    "joint",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder, org.mockito.Mockito.times(titles.size())).ingest(captor.capture());
        List<String> keys = captor.getAllValues().stream()
                .map(request -> String.valueOf(request.metadata().get("ideaKey")))
                .toList();
        assertThat(keys)
                .containsExactly(
                        "proj-rotate-redacted-out-of-ci",
                        "proj-revoke-redacted-today",
                        "proj-stop-logging-password-redacted",
                        "proj-send-bearer-redacted-only-once");
        for (EventIngestRequest request : captor.getAllValues()) {
            String keyLine = request.text()
                    .lines()
                    .filter(line -> line.startsWith("Idea key: "))
                    .findFirst()
                    .orElseThrow();
            assertThat(keyLine.toLowerCase())
                    .doesNotContain("akiaiosfodnn7example")
                    .doesNotContain(githubToken.toLowerCase().substring(4))
                    .doesNotContain("hunter2secret")
                    .doesNotContain("abcdefghijklmnopqrstuvwxyz0123");
        }
    }

    @Test
    void captureIdeaKeepsAnExplicitObservedAtAndDefaultsToNow() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-5", "session-1", "codex", "client-1", "Idea", false));
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        java.time.Instant then = java.time.Instant.parse("2026-09-20T10:15:30.123Z");
        java.time.Instant before = java.time.Instant.now();

        service.captureIdea(idea("joint", null, null), "observation-1", then);
        service.captureIdea(idea("joint", null, null));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder, org.mockito.Mockito.times(2)).ingest(captor.capture());
        assertThat(captor.getAllValues().get(0).observedAt()).isEqualTo(then);
        assertThat(captor.getAllValues().get(1).observedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void captureIdeaAcceptsLegsAtBothBounds() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-3", "session-1", "codex", "client-1", "Idea", false));
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));

        service.captureIdea(idea("joint", "tracked", 0));
        service.captureIdea(idea("joint", "tracked", 10));

        verify(recorder, org.mockito.Mockito.times(2)).ingest(any(EventIngestRequest.class));
    }

    @Test
    void captureEvidenceValidatesAndRendersMetadata() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("evidence-1", "session-1", "codex", "client-1", "Evidence", false));
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        CaptureEvidenceRequest valid = new CaptureEvidenceRequest(
                "codex",
                "client-1",
                " /repo ",
                "No matching command",
                "zero results",
                "rg run",
                "sha256:abc",
                "2026-09-28T12:00:00-04:00",
                "codex",
                List.of("IDEA: key ", "idea:key"),
                List.of("event:abcd1234"),
                "Proof",
                " Board ",
                List.of(new LaneListing(" Other ", 0.8)));
        service.captureEvidence(valid);
        ArgumentCaptor<EventIngestRequest> captured = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(captured.capture());
        assertThat(captured.getValue().eventType()).isEqualTo("Evidence");
        assertThat(captured.getValue().cwd()).isEqualTo("/repo");
        assertThat(captured.getValue().observedAt()).isAfter(Instant.parse("2026-09-28T16:00:00Z"));
        assertThat(captured.getValue().metadata()).containsEntry("observedAt", "2026-09-28T16:00:00Z");
        assertThat(captured.getValue().metadata().get("supports")).isEqualTo(List.of("idea:key"));
        assertThat(captured.getValue().metadata().get("alsoIn"))
                .isEqualTo(List.of(Map.of("project", "Other", "score", 0.8)));
        assertThat(captured.getValue().text())
                .contains(
                        "[Evidence] No matching command",
                        "Source: rg run",
                        "Project: Board",
                        "Also in: Other (0.8)",
                        "Observed: 2026-09-28T16:00:00Z");
    }

    @Test
    void emptyEvidenceListsAndBlankObservedAtAreAbsent() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("evidence-2", "session-1", "codex", "client-1", "Evidence", false));
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        service.captureEvidence(new CaptureEvidenceRequest(
                "codex",
                "client-1",
                " /repo ",
                "Fact",
                null,
                "run",
                null,
                " ",
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of()));
        ArgumentCaptor<EventIngestRequest> captured = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(captured.capture());
        assertThat(captured.getValue().metadata()).doesNotContainKeys("observedAt", "supports", "refutes", "alsoIn");
        assertThat(captured.getValue().cwd()).isEqualTo("/repo");
    }

    @Test
    void invalidEvidenceDoesNotWrite() {
        StructuredCaptureService service =
                new StructuredCaptureService(recorder, new RedactionService(new IngestionProperties()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureEvidence(new CaptureEvidenceRequest(
                        "codex", "c", null, " ", null, "run", null, null, null, null, null, null, null, null)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureEvidence(new CaptureEvidenceRequest(
                        "codex", "c", null, "fact", null, " ", null, null, null, null, null, null, null, null)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureEvidence(new CaptureEvidenceRequest(
                        "codex",
                        "c",
                        null,
                        "fact",
                        null,
                        "run",
                        "x".repeat(201),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.captureEvidence(new CaptureEvidenceRequest(
                        "codex",
                        "c",
                        null,
                        "fact",
                        null,
                        "run",
                        null,
                        "yesterday",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
        verifyNoInteractions(recorder);
    }

    private static CaptureIdeaRequest idea(String origin, String status, Integer legs) {

        return new CaptureIdeaRequest(
                "codex",
                "client-1",
                "/repo",
                "Title",
                "One liner.",
                origin,
                null,
                null,
                legs,
                status,
                null,
                null,
                null,
                null,
                null);
    }
}
