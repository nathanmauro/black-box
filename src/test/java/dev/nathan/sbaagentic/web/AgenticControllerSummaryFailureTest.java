package dev.nathan.sbaagentic.web;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:agentic-controller-summary-failure-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false",
        "sba.summary.backend=external",
        "sba.summary.external-command=exit 42"
})
@AutoConfigureMockMvc
class AgenticControllerSummaryFailureTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void summarizeExternalBackendFailureReturnsCleanServiceError() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "summary-backend-failure",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "This session should fail summary generation cleanly.",
                                  "observedAt": "2026-06-23T15:25:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/summarize")
                        .param("source", "codex")
                        .param("clientSessionId", "summary-backend-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.status").value(503))
                .andExpect(jsonPath("$.error.type").value("request_failed"))
                .andExpect(jsonPath("$.error.message").value("Summary backend failed or produced no summary"));
    }
}
