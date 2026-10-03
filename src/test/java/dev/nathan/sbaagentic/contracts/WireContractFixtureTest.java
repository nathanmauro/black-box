package dev.nathan.sbaagentic.contracts;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.ask.AskCitation;
import dev.nathan.sbaagentic.ask.AskComponentStatus;
import dev.nathan.sbaagentic.ask.AskRequest;
import dev.nathan.sbaagentic.ask.AskResponse;
import dev.nathan.sbaagentic.ask.AskRetrieveResponse;
import dev.nathan.sbaagentic.ask.AskStatus;
import dev.nathan.sbaagentic.judgment.EventJudgment;
import dev.nathan.sbaagentic.lineage.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.lineage.DagEdge;
import dev.nathan.sbaagentic.lineage.DagNode;
import dev.nathan.sbaagentic.lineage.DagResponse;
import dev.nathan.sbaagentic.lineage.LinkErrorCode;
import dev.nathan.sbaagentic.lineage.LinkType;
import dev.nathan.sbaagentic.lineage.SessionLink;
import dev.nathan.sbaagentic.lineage.SessionLinkView;
import dev.nathan.sbaagentic.lineage.SessionLinksResponse;
import dev.nathan.sbaagentic.lineage.SessionRef;
import dev.nathan.sbaagentic.memory.CompactSearchResult;
import dev.nathan.sbaagentic.memory.ElasticHealth;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillRequest;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillResult;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.SearchResponse;
import dev.nathan.sbaagentic.memory.internal.application.IdeaListResponse;
import dev.nathan.sbaagentic.memory.internal.application.IdeaMigrationResult;
import dev.nathan.sbaagentic.memory.internal.application.IdeaView;
import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.StreamEvents;
import dev.nathan.sbaagentic.platform.internal.adapter.in.web.ApiExceptionHandler;
import dev.nathan.sbaagentic.project.CodeNavigationResult;
import dev.nathan.sbaagentic.project.CodeProjectScope;
import dev.nathan.sbaagentic.project.CodeReference;
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
import dev.nathan.sbaagentic.project.ProjectTrajectoryResponse;
import dev.nathan.sbaagentic.project.TrajectoryCapture;
import dev.nathan.sbaagentic.project.TrajectoryPath;
import dev.nathan.sbaagentic.project.TrajectoryTask;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.DashboardStats;
import dev.nathan.sbaagentic.recording.EventFeedItem;
import dev.nathan.sbaagentic.recording.EventFeedResponse;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.IdempotentEventIngestRequest;
import dev.nathan.sbaagentic.recording.IdempotentIngestResponse;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.ProjectionPath;
import dev.nathan.sbaagentic.recording.StorageStats;
import dev.nathan.sbaagentic.summary.AiHealth;
import dev.nathan.sbaagentic.summary.ExportTarget;
import dev.nathan.sbaagentic.summary.SummaryBackfillResult;
import dev.nathan.sbaagentic.summary.SummaryExport;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WireContractFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void everyRestRecordFixtureContainsExactlyItsSerializedProperties() throws IOException {
        if (Boolean.getBoolean("contracts.update")) updateContinuityFixtures();
        JsonNode records = fixture().path("records");
        assertThat(toSet(records.fieldNames())).isEqualTo(recordClasses().keySet());

        recordClasses()
                .forEach((name, type) -> assertThat(toSet(records.path(name).fieldNames()))
                        .as(name)
                        .isEqualTo(serializedProperties(type)));
    }

    @Test
    void enumValuesAndSseFramesStayStable() throws IOException {
        JsonNode fixture = fixture();
        Map<String, Object[]> enums =
                Map.ofEntries(entry("LinkType", LinkType.values()), entry("LinkErrorCode", LinkErrorCode.values()));
        enums.forEach((name, values) -> assertThat(fixture.path("enums").path(name))
                .as(name)
                .isEqualTo(objectMapper.valueToTree(Arrays.asList(values))));

        Map<String, Class<?>> frames = Map.ofEntries(
                entry("event.appended", StreamEvents.EventAppended.class),
                entry("session.updated", StreamEvents.SessionUpdated.class),
                entry("judgment.appended", StreamEvents.JudgmentAppended.class));
        JsonNode sseFrames = fixture.path("sseFrames");
        assertThat(toSet(sseFrames.fieldNames())).isEqualTo(frames.keySet());
        frames.forEach((name, type) ->
                assertThat(toSet(sseFrames.path(name).fieldNames())).as(name).isEqualTo(serializedProperties(type)));
    }

    private JsonNode fixture() throws IOException {

        return Boolean.getBoolean("contracts.update")
                ? objectMapper.readTree(java.nio.file.Path.of("src/test/resources/contracts/wire-fixtures.json")
                        .toFile())
                : objectMapper.readTree(new ClassPathResource("contracts/wire-fixtures.json").getInputStream());
    }

    /** Explicit opt-in generator uses the real record serializer while retaining unrelated fixture bytes. */
    private void updateContinuityFixtures() throws IOException {
        java.nio.file.Path path = java.nio.file.Path.of("src/test/resources/contracts/wire-fixtures.json");
        JsonNode records = objectMapper.readTree(path.toFile()).path("records");
        String source = java.nio.file.Files.readString(path);
        for (String type : java.util.List.of("CaptureDecisionRequest", "RecalledItem")) {
            var fixture = (com.fasterxml.jackson.databind.node.ObjectNode)
                    records.path(type).deepCopy();
            if (type.equals("CaptureDecisionRequest")) fixture.put("supersedes", "event-prior");
            else {
                fixture.put("supersedesEventId", "event-prior");
                fixture.put("supersededByEventId", "event-next");
            }
            Object record = objectMapper.treeToValue(fixture, recordClasses().get(type));
            String serialized = objectMapper
                    .copy()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(record);
            String prefix = "    \"" + type + "\": ";
            source = source.lines()
                    .map(line -> line.startsWith(prefix) ? prefix + serialized + (line.endsWith(",") ? "," : "") : line)
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        }
        java.nio.file.Files.writeString(path, source);
    }

    private Set<String> serializedProperties(Class<?> type) {
        Set<String> properties = new TreeSet<>();
        objectMapper
                .getSerializationConfig()
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
                entry("CompactSearchResult", CompactSearchResult.class),
                entry("CompactSearchResult.Hit", CompactSearchResult.Hit.class),
                entry("CompactSearchResult.SourceReference", CompactSearchResult.SourceReference.class),
                entry("CompactSearchResult.Coverage", CompactSearchResult.Coverage.class),
                entry("AgentEvent", AgentEvent.class),
                entry("AgentSession", AgentSession.class),
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
                entry("CaptureProjectionRequest", CaptureProjectionRequest.class),
                entry("CaptureIdeaRequest", CaptureIdeaRequest.class),
                entry("CodeNavigationResult", CodeNavigationResult.class),
                entry("CodeProjectScope", CodeProjectScope.class),
                entry("CodeReference", CodeReference.class),
                entry("CreateSessionLinkRequest", CreateSessionLinkRequest.class),
                entry("DagEdge", DagEdge.class),
                entry("DagNode", DagNode.class),
                entry("DagResponse", DagResponse.class),
                entry("DashboardStats", DashboardStats.class),
                entry("DashboardStats.BreakdownCount", DashboardStats.BreakdownCount.class),
                entry("DashboardStats.DailyCount", DashboardStats.DailyCount.class),
                entry("ElasticHealth", ElasticHealth.class),
                entry("EventJudgment", EventJudgment.class),
                entry("EventFeedItem", EventFeedItem.class),
                entry("EventFeedResponse", EventFeedResponse.class),
                entry("EventIngestRequest", EventIngestRequest.class),
                entry("IngestResponse", IngestResponse.class),
                entry("IdeaListResponse", IdeaListResponse.class),
                entry("IdeaMigrationCandidate", IdeaMigrationResult.Candidate.class),
                entry("IdeaMigrationResult", IdeaMigrationResult.class),
                entry("IdeaView", IdeaView.class),
                entry("IdempotentEventIngestRequest", IdempotentEventIngestRequest.class),
                entry("IdempotentIngestResponse", IdempotentIngestResponse.class),
                entry("MemoryEmbeddingBackfillRequest", MemoryEmbeddingBackfillRequest.class),
                entry("MemoryEmbeddingBackfillResult", MemoryEmbeddingBackfillResult.class),
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
                entry("ProjectTrajectoryResponse", ProjectTrajectoryResponse.class),
                entry("ProjectionPath", ProjectionPath.class),
                entry("RecallResult", RecallResult.class),
                entry("RecalledItem", RecalledItem.class),
                entry("SearchResponse", SearchResponse.class),
                entry("SessionLink", SessionLink.class),
                entry("SessionLinksResponse", SessionLinksResponse.class),
                entry("SessionLinkView", SessionLinkView.class),
                entry("SessionRef", SessionRef.class),
                entry("StorageStats", StorageStats.class),
                entry("SummaryBackfillResult", SummaryBackfillResult.class),
                entry("SummaryExport", SummaryExport.class),
                entry("TrajectoryCapture", TrajectoryCapture.class),
                entry("TrajectoryPath", TrajectoryPath.class),
                entry("TrajectoryTask", TrajectoryTask.class),
                entry("ExportTarget", ExportTarget.class)));
    }
}
