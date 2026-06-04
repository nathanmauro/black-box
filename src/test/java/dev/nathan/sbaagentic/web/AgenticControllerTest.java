package dev.nathan.sbaagentic.web;

import java.nio.file.Files;
import java.nio.file.Path;

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
import static org.hamcrest.Matchers.hasItem;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:agentic-controller-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.elasticsearch.enabled=false",
        "sba.exports.targets[0].id=obsidian",
        "sba.exports.targets[0].label=Obsidian",
        "sba.exports.targets[0].type=markdown-file",
        "sba.exports.targets[0].directory=target/test-obsidian-export",
        "sba.exports.targets[1].id=team-wiki",
        "sba.exports.targets[1].label=Team Wiki",
        "sba.exports.targets[1].type=markdown-file",
        "sba.exports.targets[1].directory=target/test-team-wiki-export",
        "sba.exports.targets[1].filename-template={{source}}-{{slug}}-{{shortId}}.md"
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
}
