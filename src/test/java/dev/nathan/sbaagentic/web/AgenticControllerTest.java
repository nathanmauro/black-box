package dev.nathan.sbaagentic.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:agentic-controller-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false",
        "sba.exports.targets[0].id=obsidian",
        "sba.exports.targets[0].label=Obsidian",
        "sba.exports.targets[0].type=markdown-file",
        "sba.exports.targets[0].directory=target/test-obsidian-export",
        "sba.exports.targets[1].id=team-wiki",
        "sba.exports.targets[1].label=Team Wiki",
        "sba.exports.targets[1].type=markdown-file",
        "sba.exports.targets[1].directory=target/test-team-wiki-export",
        "sba.exports.targets[1].filename-template={{source}}-{{slug}}-{{shortId}}.md",
        "sba.exports.targets[2].id=unconfigured",
        "sba.exports.targets[2].label=Unconfigured",
        "sba.exports.targets[2].type=markdown-file"
})
@AutoConfigureMockMvc
class AgenticControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void ingestAndListSessions() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "turn-group-1",
                                  "eventType": "PostToolUse",
                                  "role": "agent",
                                  "text": "Compiled the app and found one Java request body issue.",
                                  "metadata": { "title": "Compile fix" },
                                  "observedAt": "2026-05-21T12:05:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("codex"))
                .andExpect(jsonPath("$.eventType").value("PostToolUse"));

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.clientSessionId == 'turn-group-1')].title").value(hasItem("Compile fix")));

        mockMvc.perform(get("/api/search").param("q", "Compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.local[0].eventType").value("PostToolUse"));
    }

    @Test
    void summarizeByClientSessionUsesSameSummaryPath() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "summarize-client-session",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "Summarize this client session at finalization.",
                                  "observedAt": "2026-05-21T12:05:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/summarize")
                        .param("source", "codex")
                        .param("clientSessionId", "summarize-client-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSessionId").value("summarize-client-session"))
                .andExpect(jsonPath("$.summary").isNotEmpty());
    }

    @Test
    void summarizeMissingBackfillsRecentUnsummarizedSessions() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "summarize-missing-session",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "This session should be summarized by the missing-summary backfill.",
                                  "observedAt": "2026-05-21T12:20:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/summarize-missing").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.summarized").value(1))
                .andExpect(jsonPath("$.sessions[0].clientSessionId").value("summarize-missing-session"))
                .andExpect(jsonPath("$.sessions[0].summary").isNotEmpty());
    }

    @Test
    void exportTargetsExposeConfiguredDestinations() throws Exception {
        mockMvc.perform(get("/api/exports/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'obsidian')].label").value(hasItem("Obsidian")))
                .andExpect(jsonPath("$[?(@.id == 'team-wiki')].label").value(hasItem("Team Wiki")));
    }

    @Test
    void exportSummaryToConfiguredMarkdownFileTargetWritesTemplatedNote() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "configured-export-session",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "Export this summary to the configured markdown file target when I choose.",
                                  "metadata": { "title": "Configured export smoke" },
                                  "observedAt": "2026-05-21T12:30:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/summarize")
                        .param("source", "codex")
                        .param("clientSessionId", "configured-export-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isNotEmpty());

        String body = mockMvc.perform(post("/api/sessions/summarize")
                        .param("source", "codex")
                        .param("clientSessionId", "configured-export-session"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String sessionId = objectMapper.readTree(body).get("id").asText();

        String exportBody = mockMvc.perform(post("/api/sessions/{sessionId}/exports/team-wiki", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.targetId").value("team-wiki"))
                .andExpect(jsonPath("$.targetLabel").value("Team Wiki"))
                .andExpect(jsonPath("$.relativePath").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode export = objectMapper.readTree(exportBody);
        Path note = Path.of(export.get("path").asText());
        String markdown = Files.readString(note);
        org.assertj.core.api.Assertions.assertThat(markdown)
                .contains("black_box_session_id: \"" + sessionId + "\"")
                .contains("client_session_id: \"configured-export-session\"")
                .contains("## Summary");
    }

    @Test
    void exportSummaryToUnconfiguredTargetReturnsCleanClientError() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "unconfigured-export-session",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "Export should fail cleanly when the target has no directory.",
                                  "metadata": { "title": "Unconfigured export smoke" },
                                  "observedAt": "2026-05-21T12:35:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/sessions/summarize")
                        .param("source", "codex")
                        .param("clientSessionId", "unconfigured-export-session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String sessionId = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(post("/api/sessions/{sessionId}/exports/unconfigured", sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.type").value("request_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value("Export directory is not configured for target: unconfigured"));
    }

    @Test
    void askStatusReportsConfiguredAskDependencies() throws Exception {
        mockMvc.perform(get("/api/ask/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryIndex").value("agent-memory"))
                .andExpect(jsonPath("$.embeddingModel").value("nomic-embed-text"))
                .andExpect(jsonPath("$.retrievalMode").value("unavailable"));
    }

    @Test
    void askRetrieveReturnsEmptyResultsWhenMemorySearchIsUnavailable() throws Exception {
        mockMvc.perform(get("/api/ask/retrieve").param("q", "history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("history"))
                .andExpect(jsonPath("$.retrievalMode").value("unavailable"))
                .andExpect(jsonPath("$.results").isArray());
    }

    @Test
    void askReturnsGroundedNoHitAnswerWhenNoMemoryHitsExist() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "What did we decide about agent memory?",
                                  "limit": 6
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Answer not found in memory."))
                .andExpect(jsonPath("$.citations").isArray())
                .andExpect(jsonPath("$.citations.length()").value(0));
    }

    /**
     * With Elasticsearch off (SBA_ELASTICSEARCH_ENABLED=false, the test default), {@code _field_caps}
     * is never consulted, so {@code /api/search/fields} must serve the curated fallback list so the
     * query bar always has fields to autocomplete. Each entry carries
     * {@code {name,type,searchable,aggregatable}}: the keyword-family fields are aggregatable, the
     * analyzed {@code text}/{@code title} fields are searchable but not aggregatable.
     */
    @Test
    void searchFieldsReturnsCuratedFallbackWhenElasticsearchOff() throws Exception {
        mockMvc.perform(get("/api/search/fields"))
                .andExpect(status().isOk())
                // Keyword-family fields: searchable + aggregatable (value-enumerable).
                .andExpect(jsonPath("$[?(@.name == 'source')].type").value(hasItem("keyword")))
                .andExpect(jsonPath("$[?(@.name == 'source')].searchable").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.name == 'source')].aggregatable").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.name == 'eventType')].type").value(hasItem("keyword")))
                .andExpect(jsonPath("$[?(@.name == 'toolName')].type").value(hasItem("keyword")))
                .andExpect(jsonPath("$[?(@.name == 'cwd')].type").value(hasItem("keyword")))
                .andExpect(jsonPath("$[?(@.name == 'clientSessionId')].type").value(hasItem("keyword")))
                .andExpect(jsonPath("$[?(@.name == 'sessionId')].type").value(hasItem("keyword")))
                .andExpect(jsonPath("$[?(@.name == 'turnId')].type").value(hasItem("keyword")))
                // Analyzed text fields: searchable but NOT aggregatable (value autocomplete yields none).
                .andExpect(jsonPath("$[?(@.name == 'title')].type").value(hasItem("text")))
                .andExpect(jsonPath("$[?(@.name == 'title')].aggregatable").value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.name == 'text')].type").value(hasItem("text")))
                .andExpect(jsonPath("$[?(@.name == 'text')].searchable").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.name == 'text')].aggregatable").value(hasItem(false)));
    }

    /**
     * With Elasticsearch off, {@code /api/search/values} falls back to the SQLite
     * {@code DISTINCT ... LIKE 'prefix%'} path. Seeding a {@code claude}-sourced event and querying
     * {@code field=source&prefix=cl} must return the distinct, prefix-matching {@code source} value,
     * proving the case-insensitive prefix path returns real stored data (not the curated field list).
     */
    @Test
    void searchValuesReturnsDistinctSqliteValuesWhenElasticsearchOff() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "claude",
                                  "clientSessionId": "search-values-session",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "Seed a claude-sourced event so value autocomplete has a prefix match.",
                                  "observedAt": "2026-05-21T12:10:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/search/values").param("field", "source").param("prefix", "cl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasItem("claude")));
    }

    /**
     * An unknown / unwhitelisted field must NOT 500 and must NOT leak SQL: the repository maps the
     * logical field name through a fixed switch (never interpolating user input as a column), so a
     * crafted, injection-shaped field resolves to no column and yields an empty list at HTTP 200.
     */
    @Test
    void searchValuesUnknownFieldReturnsEmptyWithoutSqlLeak() throws Exception {
        mockMvc.perform(get("/api/search/values")
                        .param("field", "bogus_field")
                        .param("prefix", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(empty()));

        // SQL-injection-shaped field name: still no 500, still an empty array, no leaked column data.
        mockMvc.perform(get("/api/search/values")
                        .param("field", "source); DROP TABLE agent_events;--")
                        .param("prefix", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(empty()))
                .andExpect(jsonPath("$.length()").value(is(0)));
    }

    @Test
    void statsDashboardReturnsTotalsBreakdownsAndRecentActivity() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String codexSource = "stats-codex-" + suffix;
        String claudeSource = "stats-claude-" + suffix;
        String codexSession = "stats-dashboard-one-" + suffix;
        String claudeSession = "stats-dashboard-two-" + suffix;
        String decisionKind = "StatsDecision" + suffix;
        String handoffKind = "StatsHandoff" + suffix;

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "%s",
                                  "clientSessionId": "%s",
                                  "eventType": "%s",
                                  "role": "agent",
                                  "text": "Stats dashboard decision",
                                  "observedAt": "%sT10:00:00Z"
                                }
                                """.formatted(codexSource, codexSession, decisionKind, LocalDate.now(ZoneOffset.UTC))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "%s",
                                  "clientSessionId": "%s",
                                  "eventType": "%s",
                                  "role": "agent",
                                  "text": "Stats dashboard handoff",
                                  "observedAt": "%sT10:05:00Z"
                                }
                                """.formatted(claudeSource, claudeSession, handoffKind, LocalDate.now(ZoneOffset.UTC))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSessions").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalEvents").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.eventsBySource[?(@.name == '%s')].count".formatted(codexSource)).value(hasItem(1)))
                .andExpect(jsonPath("$.eventsBySource[?(@.name == '%s')].count".formatted(claudeSource)).value(hasItem(1)))
                .andExpect(jsonPath("$.eventsByKind[?(@.name == '%s')].count".formatted(decisionKind)).value(hasItem(1)))
                .andExpect(jsonPath("$.eventsByKind[?(@.name == '%s')].count".formatted(handoffKind)).value(hasItem(1)))
                .andExpect(jsonPath("$.sessionsBySource[?(@.name == '%s')].count".formatted(codexSource)).value(hasItem(1)))
                .andExpect(jsonPath("$.sessionsBySource[?(@.name == '%s')].count".formatted(claudeSource)).value(hasItem(1)))
                .andExpect(jsonPath("$.recentActivity[?(@.day == '%s')].count".formatted(LocalDate.now(ZoneOffset.UTC)))
                        .value(hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(2))));

        mockMvc.perform(post("/api/sessions/summarize")
                        .param("source", codexSource)
                        .param("clientSessionId", codexSession))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/sessions/summarize")
                        .param("source", claudeSource)
                        .param("clientSessionId", claudeSession))
                .andExpect(status().isOk());
    }

    @Test
    void projectsReadModelGroupsSessionsAndBuildsTimeline() throws Exception {
        String cwd = "/tmp/black-box-projects-alpha";

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "projects-alpha-one",
                                  "eventType": "Decision",
                                  "role": "agent",
                                  "text": "Use SQLite source of truth",
                                  "cwd": "/tmp/black-box-projects-alpha",
                                  "metadata": {
                                    "kind": "decision",
                                    "decision": "Use SQLite source of truth",
                                    "rationale": "Raw events must remain authoritative."
                                  },
                                  "observedAt": "2026-05-21T11:00:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "projects-alpha-one",
                                  "eventType": "AssistantMessage",
                                  "role": "assistant",
                                  "text": "Implemented the reader",
                                  "cwd": "/tmp/black-box-projects-alpha",
                                  "observedAt": "2026-05-21T11:01:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "claude",
                                  "clientSessionId": "projects-alpha-two",
                                  "eventType": "PostToolUse",
                                  "role": "agent",
                                  "text": "Compiled project read model",
                                  "cwd": "/tmp/black-box-projects-alpha/",
                                  "toolName": "bash",
                                  "toolOutput": { "stdout": "build ok" },
                                  "observedAt": "2026-05-21T11:02:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "claude",
                                  "clientSessionId": "projects-alpha-two",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "User prompt should not become storyline",
                                  "cwd": "/tmp/black-box-projects-alpha/",
                                  "observedAt": "2026-05-21T11:03:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        String projectsBody = mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode project = projectByCanonicalKey(objectMapper.readTree(projectsBody), cwd);
        org.assertj.core.api.Assertions.assertThat(project).isNotNull();
        String projectKey = project.get("projectKey").asText();
        org.assertj.core.api.Assertions.assertThat(project.get("label").asText()).isEqualTo(cwd);
        org.assertj.core.api.Assertions.assertThat(project.get("sessionCount").asInt()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(project.get("eventCount").asInt()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(project.get("savedMeldCount").asInt()).isZero();

        mockMvc.perform(get("/api/projects/{projectKey}/sessions", projectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.clientSessionId == 'projects-alpha-one')].eventCount").value(hasItem(2)))
                .andExpect(jsonPath("$[?(@.clientSessionId == 'projects-alpha-two')].eventCount").value(hasItem(2)));

        String timelineBody = mockMvc.perform(get("/api/projects/{projectKey}/timeline", projectKey)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectKey").value(projectKey))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode items = objectMapper.readTree(timelineBody).get("items");
        org.assertj.core.api.Assertions.assertThat(textValues(items, "blockType"))
                .contains("decision", "assistant", "tool");
        org.assertj.core.api.Assertions.assertThat(textValues(items, "headline"))
                .contains("Use SQLite source of truth", "Implemented the reader", "Compiled project read model")
                .doesNotContain("User prompt should not become storyline");

        mockMvc.perform(get("/api/projects/{projectKey}/melds", projectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(empty()));
    }

    @Test
    void projectMeldPreviewBuildsBoundedExportBundle() throws Exception {
        String cwd = "/tmp/black-box-meld-preview-alpha";

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "meld-preview-alpha-one",
                                  "eventType": "Decision",
                                  "role": "agent",
                                  "text": "Keep export bundle mode local by default",
                                  "cwd": "/tmp/black-box-meld-preview-alpha",
                                  "metadata": {
                                    "kind": "decision",
                                    "decision": "Keep export bundle mode local by default"
                                  },
                                  "observedAt": "2026-05-20T10:00:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "meld-preview-alpha-one",
                                  "eventType": "AssistantMessage",
                                  "role": "assistant",
                                  "text": "Added a deterministic bundle builder.",
                                  "cwd": "/tmp/black-box-meld-preview-alpha",
                                  "observedAt": "2026-05-20T10:01:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "claude",
                                  "clientSessionId": "meld-preview-alpha-two",
                                  "eventType": "PostToolUse",
                                  "role": "agent",
                                  "text": "Read the project service",
                                  "cwd": "/tmp/black-box-meld-preview-alpha/",
                                  "toolName": "Read",
                                  "toolInput": { "file_path": "src/main/java/dev/nathan/sbaagentic/project/ProjectService.java" },
                                  "toolOutput": { "result": "ok" },
                                  "observedAt": "2026-05-20T10:02:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        JsonNode project = projectByCanonicalKey(
                objectMapper.readTree(mockMvc.perform(get("/api/projects"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()),
                cwd);
        String projectKey = project.get("projectKey").asText();
        JsonNode sessions = objectMapper.readTree(mockMvc.perform(get("/api/projects/{projectKey}/sessions", projectKey))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        List<String> sessionIds = textValues(sessions, "id");

        mockMvc.perform(post("/api/projects/{projectKey}/melds/preview", projectKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionIds", sessionIds,
                                "executionMode", "export_bundle"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("bundle"))
                .andExpect(jsonPath("$.executionMode").value("export_bundle"))
                .andExpect(jsonPath("$.provider").value("local"))
                .andExpect(jsonPath("$.model").value("context-bundle"))
                .andExpect(jsonPath("$.projectKey").value(projectKey))
                .andExpect(jsonPath("$.sessionCount").value(2))
                .andExpect(jsonPath("$.evidenceCount").value(3))
                .andExpect(jsonPath("$.bundle").value(org.hamcrest.Matchers.containsString("# Project Meld Bundle")))
                .andExpect(jsonPath("$.bundle").value(org.hamcrest.Matchers.containsString("Keep export bundle mode local by default")))
                .andExpect(jsonPath("$.bundle").value(org.hamcrest.Matchers.containsString("ProjectService.java")))
                .andExpect(jsonPath("$.degradationNotes").isArray());
    }

    @Test
    void projectMeldPreviewRejectsCrossProjectSessionSelection() throws Exception {
        String cwd = "/tmp/black-box-meld-cross-alpha";
        String otherCwd = "/tmp/black-box-meld-cross-beta";

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "meld-cross-alpha-one",
                                  "eventType": "Decision",
                                  "role": "agent",
                                  "text": "Alpha decision",
                                  "cwd": "/tmp/black-box-meld-cross-alpha",
                                  "observedAt": "2026-05-20T11:00:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "meld-cross-beta-one",
                                  "eventType": "Decision",
                                  "role": "agent",
                                  "text": "Beta decision",
                                  "cwd": "/tmp/black-box-meld-cross-beta",
                                  "observedAt": "2026-05-20T11:01:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        JsonNode projects = objectMapper.readTree(mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String projectKey = projectByCanonicalKey(projects, cwd).get("projectKey").asText();
        String otherProjectKey = projectByCanonicalKey(projects, otherCwd).get("projectKey").asText();
        String foreignSessionId = objectMapper.readTree(mockMvc.perform(get("/api/projects/{projectKey}/sessions", otherProjectKey))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get(0)
                .get("id")
                .asText();

        mockMvc.perform(post("/api/projects/{projectKey}/melds/preview", projectKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionIds", List.of(foreignSessionId),
                                "executionMode", "export_bundle"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("Selected sessions must belong to this project")));
    }

    private JsonNode projectByCanonicalKey(JsonNode projects, String canonicalKey) {
        for (JsonNode project : projects) {
            if (canonicalKey.equals(project.path("canonicalKey").asText())) {
                return project;
            }
        }
        return null;
    }

    private List<String> textValues(JsonNode items, String fieldName) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            values.add(item.path(fieldName).asText());
        }
        return values;
    }
}
