package dev.nathan.sbaagentic.project.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.project.ProjectTimelineBlock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-project-timeline-query-plan-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false"
        })
class ProjectTimelineQueryPlanTest {

    private static final String OLD_STORYLINE_PREDICATE = """
            (
              lower(coalesce(e.event_type, '')) IN ('decision', 'handoff')
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"decision"%'
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"handoff"%'
              OR (lower(coalesce(e.role, '')) = 'assistant' AND trim(coalesce(e.text, '')) <> '')
              OR e.tool_name IS NOT NULL
              OR lower(coalesce(e.event_type, '')) LIKE '%tool%'
              OR lower(coalesce(e.event_type, '')) LIKE '%error%'
              OR lower(coalesce(e.event_type, '')) LIKE '%fail%'
            )
            """;

    private static final String ROOT = "/tmp/black-box-timeline-plan-root";
    private static final String ALIAS_ONE = "/tmp/black-box-timeline-plan-alias-one";
    private static final String ALIAS_TWO = "/tmp/black-box-timeline-plan-alias-two";
    private static final String OTHER = "/tmp/black-box-timeline-plan-other";
    private static final String ROOT_SESSION = "timeline-plan-root";
    private static final String ALIAS_ONE_SESSION = "timeline-plan-alias-one";
    private static final String ALIAS_TWO_SESSION = "timeline-plan-alias-two";
    private static final String OTHER_SESSION = "timeline-plan-other";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectRepository repository;

    @BeforeEach
    void seedTimelineProjects() {
        jdbcTemplate.update("DELETE FROM session_meld_inputs");
        jdbcTemplate.update("DELETE FROM session_melds");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
        jdbcTemplate.update("DELETE FROM project_aliases");

        insertSession(ROOT_SESSION, "Root timeline session", ROOT, 15);
        insertSession(ALIAS_ONE_SESSION, "Alias one timeline session", ALIAS_ONE, 1);
        insertSession(ALIAS_TWO_SESSION, "Alias two timeline session", ALIAS_TWO, 1);
        insertSession(OTHER_SESSION, "Other timeline session", OTHER, 1);
        insertAlias("timeline-plan-alias-one", ALIAS_ONE);
        insertAlias("timeline-plan-alias-two", ALIAS_TWO);

        insertEvent("typed-decision", ROOT_SESSION, "dEcIsIoN", null, null, null, null, "2026-08-05T10:00:00Z");
        insertEvent(
                "metadata-decision",
                ROOT_SESSION,
                "UserPromptSubmit",
                "user",
                null,
                null,
                "{\"KiNd\":\"DeCiSiOn\"}",
                "2026-08-05T10:01:00Z");
        insertEvent(
                "assistant-text",
                ROOT_SESSION,
                "AssistantMessage",
                "AsSiStAnT",
                "Implemented the indexed timeline query",
                null,
                null,
                "2026-08-05T10:02:00Z");
        insertEvent(
                "assistant-whitespace",
                ROOT_SESSION,
                "Unknown",
                "assistant",
                "   ",
                null,
                null,
                "2026-08-05T10:03:00Z");
        insertEvent(
                "assistant-empty",
                ROOT_SESSION,
                "AssistantMessage",
                "assistant",
                "",
                null,
                null,
                "2026-08-05T10:04:00Z");
        insertEvent(
                "assistant-null-text",
                ROOT_SESSION,
                "AssistantMessage",
                "assistant",
                null,
                null,
                null,
                "2026-08-05T10:05:00Z");
        insertEvent(
                "large-tool-metadata",
                ROOT_SESSION,
                "PostUse",
                "user",
                null,
                "sqlite3",
                largeMetadata(),
                "2026-08-05T10:06:00Z");
        insertEvent(
                "large-metadata-noise",
                ROOT_SESSION,
                "UserPromptSubmit",
                "user",
                null,
                null,
                largeMetadata(),
                "2026-08-05T10:06:30Z");
        insertEvent("event-type-tool", ROOT_SESSION, "PreToOlUse", null, null, null, null, "2026-08-05T10:07:00Z");
        insertEvent(
                "event-type-error",
                ROOT_SESSION,
                "RuntimeErRoRReported",
                "user",
                null,
                null,
                null,
                "2026-08-05T10:08:00Z");
        insertEvent("event-type-fail", ROOT_SESSION, "HardFaIlure", "user", null, null, null, "2026-08-05T10:09:00Z");
        insertEvent(
                "metadata-handoff",
                ROOT_SESSION,
                "UserPromptSubmit",
                "user",
                null,
                null,
                "{\"KIND\":\"HaNdOfF\"}",
                "2026-08-05T10:10:00Z");
        insertEvent(
                "metadata-guard-miss-assistant",
                ROOT_SESSION,
                "AssistantMessage",
                "assistant",
                "The metadata guard must stay last",
                null,
                "{\"kind\":\"noise\"}",
                "2026-08-05T10:11:00Z");
        insertEvent(
                "metadata-guard-miss-noise",
                ROOT_SESSION,
                "UserPromptSubmit",
                "user",
                null,
                null,
                "{\"kind\":\"noise\"}",
                "2026-08-05T10:12:00Z");
        insertEvent("null-columns-noise", ROOT_SESSION, "Unknown", null, null, null, null, "2026-08-05T10:13:00Z");

        insertEvent(
                "alias-one-typed-handoff",
                ALIAS_ONE_SESSION,
                "hAnDoFf",
                null,
                null,
                null,
                null,
                "2026-08-05T10:14:00Z");
        insertEvent(
                "alias-two-metadata-handoff",
                ALIAS_TWO_SESSION,
                "UserPromptSubmit",
                "user",
                null,
                null,
                "{\"Kind\":\"HANDOFF\"}",
                "2026-08-05T10:15:00Z");
        insertEvent("other-typed-decision", OTHER_SESSION, "DeCiSiOn", null, null, null, null, "2026-08-05T10:16:00Z");

        insertMeld("root-meld", ROOT, "Root synthesis", "2026-08-05T10:06:45Z");
        insertMeld("alias-two-meld", ALIAS_TWO, "Alias synthesis", "2026-08-05T10:14:30Z");
    }

    @Test
    void shortCircuitedStorylinePredicateSelectsExactlyTheOldPredicateRows() {
        List<String> oldIds = matchingIds(OLD_STORYLINE_PREDICATE);
        List<String> shortCircuitedIds = matchingIds(ProjectRepository.STORYLINE_PREDICATE);

        assertThat(shortCircuitedIds).containsExactlyElementsOf(oldIds);
        assertThat(shortCircuitedIds)
                .containsExactly(
                        "alias-one-typed-handoff",
                        "alias-two-metadata-handoff",
                        "assistant-text",
                        "event-type-error",
                        "event-type-fail",
                        "event-type-tool",
                        "large-tool-metadata",
                        "metadata-decision",
                        "metadata-guard-miss-assistant",
                        "metadata-handoff",
                        "other-typed-decision",
                        "typed-decision");
        assertThat(shortCircuitedIds)
                .doesNotContain(
                        "assistant-whitespace",
                        "assistant-empty",
                        "assistant-null-text",
                        "large-metadata-noise",
                        "metadata-guard-miss-noise",
                        "null-columns-noise");
        assertPredicateEquivalent(0, null, null, null, null, null);
        assertPredicateEquivalent(1, null, "assistant", "Visible assistant text", null, null);
        assertThat(ProjectRepository.STORYLINE_PREDICATE)
                .containsSubsequence(
                        "WHEN lower(coalesce(e.event_type, '')) IN ('decision', 'handoff') THEN 1",
                        "WHEN lower(coalesce(e.role, '')) = 'assistant'",
                        "WHEN e.tool_name IS NOT NULL THEN 1",
                        "WHEN lower(coalesce(e.event_type, '')) LIKE '%tool%'",
                        "WHEN e.metadata_json LIKE '%\"kind\":\"%' THEN (",
                        "ELSE 0");
        assertThat(ProjectRepository.STORYLINE_PREDICATE).doesNotContain("instr(");
        assertThat(ProjectRepository.STORYLINE_PREDICATE.lastIndexOf("WHEN "))
                .isEqualTo(ProjectRepository.STORYLINE_PREDICATE.indexOf("WHEN e.metadata_json LIKE '%\"kind\":\"%'"));
    }

    @Test
    void timelineQueriesKeepOldResultsCountsOrderingPaginationJoinsAliasScopesAndIndexPlans() {
        List<ProjectTimelineBlock> fullTimeline = repository.timelineBlocks(ROOT, 50, 0);
        List<String> fullIds = ids(fullTimeline);

        assertThat(repository.countTimelineBlocks(ROOT)).isEqualTo(13);
        assertThat(fullIds)
                .containsExactly(
                        "typed-decision",
                        "metadata-decision",
                        "assistant-text",
                        "large-tool-metadata",
                        "root-meld",
                        "event-type-tool",
                        "event-type-error",
                        "event-type-fail",
                        "metadata-handoff",
                        "metadata-guard-miss-assistant",
                        "alias-one-typed-handoff",
                        "alias-two-meld",
                        "alias-two-metadata-handoff");
        assertThat(fullIds)
                .doesNotContain(
                        "assistant-whitespace",
                        "large-metadata-noise",
                        "metadata-guard-miss-noise",
                        "other-typed-decision");
        assertThat(block(fullTimeline, "typed-decision").sessionTitle()).isEqualTo("Root timeline session");
        assertThat(block(fullTimeline, "alias-one-typed-handoff").sessionTitle())
                .isEqualTo("Alias one timeline session");
        assertThat(block(fullTimeline, "root-meld").sourceType()).isEqualTo("saved_meld");

        List<String> pagedIds = Stream.of(
                        repository.timelineBlocks(ROOT, 4, 0),
                        repository.timelineBlocks(ROOT, 4, 4),
                        repository.timelineBlocks(ROOT, 4, 8),
                        repository.timelineBlocks(ROOT, 4, 12))
                .flatMap(List::stream)
                .map(ProjectTimelineBlock::id)
                .toList();
        assertThat(pagedIds).containsExactlyElementsOf(fullIds).doesNotHaveDuplicates();

        List<String> rootSessionIds = ids(repository.timelineBlocksForSession(ROOT, ROOT_SESSION, 50));
        assertThat(rootSessionIds)
                .containsExactly(
                        "typed-decision",
                        "metadata-decision",
                        "assistant-text",
                        "large-tool-metadata",
                        "event-type-tool",
                        "event-type-error",
                        "event-type-fail",
                        "metadata-handoff",
                        "metadata-guard-miss-assistant");
        assertThat(ids(repository.timelineBlocksForSession(ROOT, ALIAS_ONE_SESSION, 50)))
                .containsExactly("alias-one-typed-handoff");
        assertThat(repository.timelineBlocksForSession(ROOT, OTHER_SESSION, 50)).isEmpty();
        assertThat(repository.timelineBlocksForSession(OTHER, ROOT_SESSION, 50)).isEmpty();
        assertThat(repository.countTimelineBlocks(OTHER)).isEqualTo(1);

        String oldCountSql = withOldStorylinePredicate(ProjectRepository.countTimelineBlocksSql(3));
        Long oldCount = jdbcTemplate.queryForObject(
                oldCountSql, Long.class, ROOT, ALIAS_ONE, ALIAS_TWO, ROOT, ALIAS_ONE, ALIAS_TWO);
        assertThat(oldCount).isEqualTo(repository.countTimelineBlocks(ROOT));

        String oldTimelineSql = withOldStorylinePredicate(ProjectRepository.timelineBlocksSql(3));
        assertThat(queryIds(oldTimelineSql, ROOT, ALIAS_ONE, ALIAS_TWO, ROOT, ALIAS_ONE, ALIAS_TWO, 50, 0))
                .containsExactlyElementsOf(fullIds);

        String oldSessionSql = withOldStorylinePredicate(ProjectRepository.timelineBlocksForSessionSql(3));
        assertThat(queryIds(oldSessionSql, ROOT, ALIAS_ONE, ALIAS_TWO, ROOT_SESSION, 50))
                .containsExactlyElementsOf(rootSessionIds);

        assertUsesSessionEventIndex(explain(
                ProjectRepository.countTimelineBlocksSql(3), ROOT, ALIAS_ONE, ALIAS_TWO, ROOT, ALIAS_ONE, ALIAS_TWO));
        assertUsesSessionEventIndex(explain(
                ProjectRepository.timelineBlocksSql(3), ROOT, ALIAS_ONE, ALIAS_TWO, ROOT, ALIAS_ONE, ALIAS_TWO, 50, 0));
        assertUsesSessionEventIndex(explain(
                ProjectRepository.timelineBlocksForSessionSql(3), ROOT, ALIAS_ONE, ALIAS_TWO, ROOT_SESSION, 50));
    }

    private void insertSession(String sessionId, String title, String cwd, int eventCount) {
        jdbcTemplate.update(
                """
                INSERT INTO agent_sessions
                       (id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                "codex",
                sessionId,
                title,
                cwd,
                "2026-08-05T10:00:00Z",
                "2026-08-05T10:20:00Z",
                eventCount);
    }

    private void insertAlias(String id, String alias) {
        jdbcTemplate.update("""
                INSERT INTO project_aliases (id, alias_key, canonical_key, source, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, alias, ROOT, "manual", "2026-08-05T09:00:00Z");
    }

    private void insertEvent(
            String eventId,
            String sessionId,
            String eventType,
            String role,
            String text,
            String toolName,
            String metadataJson,
            String observedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO agent_events
                       (id, session_id, source, client_session_id, event_type, role, text,
                        tool_name, metadata_json, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, sessionId, "codex", sessionId, eventType, role, text, toolName, metadataJson, observedAt);
    }

    private void insertMeld(String id, String projectKey, String title, String createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO session_melds
                       (id, project_key, title, body, provider, model, prompt_version,
                        execution_mode, saved_from_preview, metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                projectKey,
                title,
                "Saved timeline synthesis",
                "local",
                "context-bundle",
                "project-meld-v1",
                "export_bundle",
                1,
                null,
                createdAt);
    }

    private List<String> matchingIds(String predicate) {

        return jdbcTemplate.query(
                "SELECT e.id FROM agent_events e WHERE " + predicate + " ORDER BY e.id",
                (rs, rowNum) -> rs.getString("id"));
    }

    private void assertPredicateEquivalent(
            int expected, String eventType, String role, String eventText, String toolName, String metadataJson) {
        Integer oldResult =
                predicateResult(OLD_STORYLINE_PREDICATE, eventType, role, eventText, toolName, metadataJson);
        Integer shortCircuitedResult = predicateResult(
                ProjectRepository.STORYLINE_PREDICATE, eventType, role, eventText, toolName, metadataJson);
        assertThat(shortCircuitedResult).isEqualTo(oldResult).isEqualTo(expected);
    }

    private Integer predicateResult(
            String predicate, String eventType, String role, String eventText, String toolName, String metadataJson) {

        return jdbcTemplate.queryForObject(
                """
                SELECT %s AS matched
                  FROM (SELECT ? AS event_type,
                               ? AS role,
                               ? AS text,
                               ? AS tool_name,
                               ? AS metadata_json) e
                """.formatted(predicate), Integer.class, eventType, role, eventText, toolName, metadataJson);
    }

    private List<String> queryIds(String sql, Object... args) {

        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("id"), args);
    }

    private List<String> explain(String sql, Object... args) {

        return jdbcTemplate.query("EXPLAIN QUERY PLAN " + sql, (rs, rowNum) -> rs.getString("detail"), args);
    }

    private static String withOldStorylinePredicate(String sql) {
        assertThat(sql).contains(ProjectRepository.STORYLINE_PREDICATE);

        return sql.replace(ProjectRepository.STORYLINE_PREDICATE, OLD_STORYLINE_PREDICATE);
    }

    private static String largeMetadata() {

        return "{\"payload\":\"" + "x".repeat(256 * 1024) + "\"}";
    }

    private static void assertUsesSessionEventIndex(List<String> plan) {
        assertThat(plan)
                .anyMatch(detail -> detail.contains("SEARCH e USING INDEX idx_agent_events_session_observed"))
                .noneMatch(detail -> detail.startsWith("SCAN e"));
    }

    private static ProjectTimelineBlock block(List<ProjectTimelineBlock> blocks, String id) {

        return blocks.stream()
                .filter(block -> id.equals(block.id()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> ids(List<ProjectTimelineBlock> blocks) {

        return blocks.stream().map(ProjectTimelineBlock::id).toList();
    }
}
