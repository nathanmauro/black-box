package dev.nathan.sbaagentic.contracts;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.ai.AiHealth;
import dev.nathan.sbaagentic.ai.SessionSummaryService;
import dev.nathan.sbaagentic.ask.AskCitation;
import dev.nathan.sbaagentic.ask.AskComponentStatus;
import dev.nathan.sbaagentic.ask.AskRequest;
import dev.nathan.sbaagentic.ask.AskResponse;
import dev.nathan.sbaagentic.ask.AskRetrieveResponse;
import dev.nathan.sbaagentic.ask.AskStatus;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.context.RecallResult;
import dev.nathan.sbaagentic.context.RecalledItem;
import dev.nathan.sbaagentic.workflow.DagEdge;
import dev.nathan.sbaagentic.workflow.DagNode;
import dev.nathan.sbaagentic.workflow.DagResponse;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.DashboardStats;
import dev.nathan.sbaagentic.recording.EventFeedItem;
import dev.nathan.sbaagentic.recording.EventFeedResponse;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.StorageStats;
import dev.nathan.sbaagentic.exporting.SummaryExportService;
import dev.nathan.sbaagentic.workflow.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.workflow.LinkErrorCode;
import dev.nathan.sbaagentic.workflow.LinkType;
import dev.nathan.sbaagentic.workflow.SessionLink;
import dev.nathan.sbaagentic.workflow.SessionLinksResponse;
import dev.nathan.sbaagentic.workflow.SessionLinkView;
import dev.nathan.sbaagentic.workflow.SessionRef;
import dev.nathan.sbaagentic.project.ProjectAlias;
import dev.nathan.sbaagentic.project.ProjectAliasRequest;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewRequest;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewResponse;
import dev.nathan.sbaagentic.project.ProjectMeldSaveRequest;
import dev.nathan.sbaagentic.project.ProjectMeldSessionRef;
import dev.nathan.sbaagentic.project.ProjectSavedMeld;
import dev.nathan.sbaagentic.project.ProjectScope;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.ProjectTimelineBlock;
import dev.nathan.sbaagentic.project.ProjectTimelineResponse;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.search.ElasticHealth;
import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.stream.StreamEvents;
import dev.nathan.sbaagentic.workflow.AnnotationKind;
import dev.nathan.sbaagentic.workflow.ClaimTaskRequest;
import dev.nathan.sbaagentic.workflow.SpecStatus;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskAnnotation;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskErrorCode;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;
import dev.nathan.sbaagentic.web.ApiExceptionHandler;
import dev.nathan.sbaagentic.workflow.internal.adapter.in.web.TaskController;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class WireContractFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void everyRestRecordFixtureContainsExactlyItsSerializedProperties() throws IOException {
        JsonNode records = fixture().path("records");
        assertThat(toSet(records.fieldNames())).isEqualTo(recordClasses().keySet());

        recordClasses().forEach((name, type) -> assertThat(toSet(records.path(name).fieldNames()))
                .as(name)
                .isEqualTo(serializedProperties(type)));
    }

    @Test
    void enumValuesSseFramesAndRunnerKeysStayStable() throws IOException {
        JsonNode fixture = fixture();
        Map<String, Object[]> enums = Map.ofEntries(
                entry("SpecStatus", SpecStatus.values()),
                entry("TaskStatus", TaskStatus.values()),
                entry("TaskEventType", TaskEventType.values()),
                entry("AnnotationKind", AnnotationKind.values()),
                entry("TaskErrorCode", TaskErrorCode.values()),
                entry("LinkType", LinkType.values()),
                entry("LinkErrorCode", LinkErrorCode.values()));
        enums.forEach((name, values) -> assertThat(fixture.path("enums").path(name))
                .as(name)
                .isEqualTo(objectMapper.valueToTree(Arrays.asList(values))));

        Map<String, Class<?>> frames = Map.ofEntries(
                entry("event.appended", StreamEvents.EventAppended.class),
                entry("session.updated", StreamEvents.SessionUpdated.class),
                entry("task.created", StreamEvents.TaskChanged.class),
                entry("task.claimed", StreamEvents.TaskChanged.class),
                entry("task.blocked", StreamEvents.TaskChanged.class),
                entry("task.completed", StreamEvents.TaskChanged.class),
                entry("task.reset", StreamEvents.TaskChanged.class),
                entry("task.cancelled", StreamEvents.TaskChanged.class),
                entry("task.note", StreamEvents.TaskNoted.class));
        JsonNode sseFrames = fixture.path("sseFrames");
        assertThat(toSet(sseFrames.fieldNames())).isEqualTo(frames.keySet());
        frames.forEach((name, type) -> assertThat(toSet(sseFrames.path(name).fieldNames()))
                .as(name)
                .isEqualTo(serializedProperties(type)));

        JsonNode runnerFixture = fixture.path("runnerConfig");
        RunnerConfig config = objectMapper.treeToValue(runnerFixture, RunnerConfig.class);
        assertThat(toSet(runnerFixture.fieldNames())).containsExactlyInAnyOrder(
                "concurrency", "engines", "notify", "repos");
        assertThat(toSet(runnerFixture.path("repos").get(0).fieldNames())).containsExactlyInAnyOrder(
                "path", "push", "auto_merge", "verify", "danger");
        assertThat(config.notifyCommand()).isEqualTo("notify-send refactor-complete");
        assertThat(config.repos().get(0).autoMerge()).isTrue();
    }

    private JsonNode fixture() throws IOException {
        return objectMapper.readTree(new ClassPathResource("contracts/wire-fixtures.json").getInputStream());
    }

    private Set<String> serializedProperties(Class<?> type) {
        Set<String> properties = new TreeSet<>();
        objectMapper.getSerializationConfig()
                .introspect(objectMapper.constructType(type))
                .findProperties()
                .forEach(property -> properties.add(property.getName()));
        return properties;
    }

    private static Set<String> toSet(java.util.Iterator<String> names) {
        Set<String> values = new TreeSet<>();
        names.forEachRemaining(values::add);
        return values;
    }

    private static Map<String, Class<?>> recordClasses() {
        return new LinkedHashMap<>(Map.ofEntries(
                entry("AgentEvent", AgentEvent.class),
                entry("AgentSession", AgentSession.class),
                entry("TaskController.AnnotationBody", TaskController.AnnotationBody.class),
                entry("TaskController.CompleteTaskBody", TaskController.CompleteTaskBody.class),
                entry("TaskController.TaskStatusBody", TaskController.TaskStatusBody.class),
                entry("AiHealth", AiHealth.class),
                entry("ApiError", ApiExceptionHandler.ApiError.class),
                entry("ApiError.ErrorBody", ApiExceptionHandler.ApiError.ErrorBody.class),
                entry("AskCitation", AskCitation.class),
                entry("AskComponentStatus", AskComponentStatus.class),
                entry("AskRequest", AskRequest.class),
                entry("AskResponse", AskResponse.class),
                entry("AskRetrieveResponse", AskRetrieveResponse.class),
                entry("AskStatus", AskStatus.class),
                entry("CaptureDecisionRequest", CaptureDecisionRequest.class),
                entry("CaptureHandoffRequest", CaptureHandoffRequest.class),
                entry("ClaimTaskRequest", ClaimTaskRequest.class),
                entry("CreateSessionLinkRequest", CreateSessionLinkRequest.class),
                entry("DagEdge", DagEdge.class),
                entry("DagNode", DagNode.class),
                entry("DagResponse", DagResponse.class),
                entry("DashboardStats", DashboardStats.class),
                entry("DashboardStats.BreakdownCount", DashboardStats.BreakdownCount.class),
                entry("DashboardStats.DailyCount", DashboardStats.DailyCount.class),
                entry("ElasticHealth", ElasticHealth.class),
                entry("EventFeedItem", EventFeedItem.class),
                entry("EventFeedResponse", EventFeedResponse.class),
                entry("EventIngestRequest", EventIngestRequest.class),
                entry("IngestResponse", IngestResponse.class),
                entry("ProjectAlias", ProjectAlias.class),
                entry("ProjectAliasRequest", ProjectAliasRequest.class),
                entry("ProjectMeldPreviewRequest", ProjectMeldPreviewRequest.class),
                entry("ProjectMeldPreviewResponse", ProjectMeldPreviewResponse.class),
                entry("ProjectMeldSaveRequest", ProjectMeldSaveRequest.class),
                entry("ProjectMeldSessionRef", ProjectMeldSessionRef.class),
                entry("ProjectSavedMeld", ProjectSavedMeld.class),
                entry("ProjectScope", ProjectScope.class),
                entry("ProjectSummary", ProjectSummary.class),
                entry("ProjectTimelineBlock", ProjectTimelineBlock.class),
                entry("ProjectTimelineResponse", ProjectTimelineResponse.class),
                entry("RecallResult", RecallResult.class),
                entry("RecalledItem", RecalledItem.class),
                entry("SearchResponse", SearchResponse.class),
                entry("SessionLink", SessionLink.class),
                entry("SessionLinksResponse", SessionLinksResponse.class),
                entry("SessionLinkView", SessionLinkView.class),
                entry("SessionRef", SessionRef.class),
                entry("StorageStats", StorageStats.class),
                entry("SummaryBackfillResult", SessionSummaryService.SummaryBackfillResult.class),
                entry("SummaryExport", SummaryExportService.SummaryExport.class),
                entry("ExportTarget", SummaryExportService.ExportTarget.class),
                entry("Task", Task.class),
                entry("TaskAnnotation", TaskAnnotation.class),
                entry("TaskChange", TaskChange.class),
                entry("TaskEvent", TaskEvent.class),
                entry("TaskSnapshot", TaskSnapshot.class),
                entry("TaskSpec", TaskSpec.class)));
    }
}
