package dev.nathan.sbaagentic.web;

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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:agentic-controller-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.elasticsearch.enabled=false"
})
@AutoConfigureMockMvc
class AgenticControllerTest {

    @Autowired
    MockMvc mockMvc;

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
                .andExpect(jsonPath("$[0].title").value("Compile fix"));

        mockMvc.perform(get("/api/search").param("q", "Compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.local[0].eventType").value("PostToolUse"));
    }
}
