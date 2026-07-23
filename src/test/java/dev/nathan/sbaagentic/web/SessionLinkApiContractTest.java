package dev.nathan.sbaagentic.web;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/session-link-api-contract-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false"
})
@AutoConfigureMockMvc
class SessionLinkApiContractTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetLinks() {
        jdbcTemplate.update("DELETE FROM session_links");
    }

    @Test
    void createAndReadLinksWithoutExistingSessions() throws Exception {
        String parentSessionId = "missing-parent-" + UUID.randomUUID();
        String childSessionId = "missing-child-" + UUID.randomUUID();

        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(parentSessionId, childSessionId, "spawned", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.parentSessionId").value(parentSessionId))
                .andExpect(jsonPath("$.childSessionId").value(childSessionId))
                .andExpect(jsonPath("$.linkType").value("spawned"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mockMvc.perform(get("/api/sessions/{sessionId}/links", childSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parents.length()").value(1))
                .andExpect(jsonPath("$.parents[0].session.id").value(parentSessionId))
                .andExpect(jsonPath("$.parents[0].session.title").isEmpty())
                .andExpect(jsonPath("$.parents[0].session.source").isEmpty())
                .andExpect(jsonPath("$.parents[0].linkType").value("spawned"))
                .andExpect(jsonPath("$.children").isEmpty());

        mockMvc.perform(get("/api/sessions/{sessionId}/links", parentSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parents").isEmpty())
                .andExpect(jsonPath("$.children.length()").value(1))
                .andExpect(jsonPath("$.children[0].session.id").value(childSessionId))
                .andExpect(jsonPath("$.children[0].linkType").value("spawned"));
    }

    @Test
    void unknownLinkTypeReturnsValidationFailure() throws Exception {
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent", "child", "branched", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
    }

    @Test
    void missingSessionIdsReturnValidationFailure() throws Exception {
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childSessionId\":\"child\",\"linkType\":\"spawned\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));

        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentSessionId\":\"parent\",\"linkType\":\"spawned\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
    }

    @Test
    void duplicateLinkReturnsConflict() throws Exception {
        String request = linkJson("parent", "child", "spawned", null);
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("duplicate_link"));
    }

    @Test
    void taskIdRoundTripsThroughCreateAndRead() throws Exception {
        String taskId = "task-" + UUID.randomUUID();
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent", "child", "continued", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId));

        mockMvc.perform(get("/api/sessions/child/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parents[0].taskId").value(taskId));
    }

    @Test
    void childCountsGroupsChildrenByParentForRequestedIds() throws Exception {
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent-a", "child-1", "spawned", null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent-a", "child-2", "spawned", null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent-b", "child-3", "continued", null)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/session-links/child-counts")
                        .param("ids", "parent-a,parent-b,parent-none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['parent-a']").value(2))
                .andExpect(jsonPath("$['parent-b']").value(1))
                .andExpect(jsonPath("$['parent-none']").doesNotExist());
    }

    @Test
    void childCountsRequiresIdsAndReturnsEmptyObjectForBlankIds() throws Exception {
        mockMvc.perform(get("/api/session-links/child-counts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("missing_parameter"));

        mockMvc.perform(get("/api/session-links/child-counts").param("ids", ""))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    private String linkJson(
            String parentSessionId,
            String childSessionId,
            String linkType,
            String taskId) throws Exception {
        return objectMapper.writeValueAsString(new LinkBody(
                parentSessionId, childSessionId, linkType, taskId));
    }

    private record LinkBody(
            String parentSessionId,
            String childSessionId,
            String linkType,
            String taskId) {
    }
}
