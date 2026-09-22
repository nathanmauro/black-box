package dev.nathan.sbaagentic.recording.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.ProjectionPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StructuredCaptureServiceTest {

    @Mock
    EventRecorder recorder;

    @Test
    void captureProjectionCapsPathsAndStoresProjectionMetadata() {
        when(recorder.ingest(any(EventIngestRequest.class)))
                .thenReturn(new IngestResponse("event-1", "session-1", "codex", "client-1", "Projection", false));

        StructuredCaptureService service = new StructuredCaptureService(recorder);
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
        StructuredCaptureService service = new StructuredCaptureService(recorder);

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

        StructuredCaptureService service = new StructuredCaptureService(recorder);
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

        StructuredCaptureService service = new StructuredCaptureService(recorder);
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
}
