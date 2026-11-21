package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Broadcasts over REST and MCP, and their attachment to recall, on an isolated temp database. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-broadcast-api-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class BroadcastApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ToolCallbackProvider callbacks;

    @Test
    void putAndPostUpsertByKeyAndGetListsTheOpenSet() throws Exception {
        String repo = uniqueRepo();

        mockMvc.perform(put("/api/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(repo, "dirty-main", "hazard", "Main has uncommitted edits.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("dirty-main"))
                .andExpect(jsonPath("$.kind").value("hazard"))
                .andExpect(jsonPath("$.severity").value("warn"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.projectScope").value(repo));
        mockMvc.perform(post("/api/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(repo, "dirty-main", "hazard", "Main is clean except one file.")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/broadcasts").param("project", repo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.broadcasts[0].message").value("Main is clean except one file."));
    }

    @Test
    void deleteAndPostClearAreIdempotent() throws Exception {
        String repo = uniqueRepo();
        set(repo, "lock-a", "hazard", "Lock A.");
        set(repo, "lock-b", "hazard", "Lock B.");

        mockMvc.perform(delete("/api/broadcasts")
                        .param("repo", repo)
                        .param("key", "lock-a")
                        .param("reason", "released"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true))
                .andExpect(jsonPath("$.broadcast.clearReason").value("released"));
        mockMvc.perform(delete("/api/broadcasts").param("repo", repo).param("key", "lock-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(false));

        String clear = """
            {"source":"codex","clientSessionId":"s-9","repo":"%s","key":"lock-b","reason":"done"}
            """.formatted(repo);
        mockMvc.perform(post("/api/broadcasts/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true))
                .andExpect(jsonPath("$.broadcast.clearedBy").value("codex:s-9"));
        mockMvc.perform(post("/api/broadcasts/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(false));

        mockMvc.perform(get("/api/broadcasts").param("project", repo))
                .andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(get("/api/broadcasts").param("project", repo).param("includeCleared", "true"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void tentacleListingSpansProjectsAndFiltersByKind() throws Exception {
        String first = uniqueRepo();
        String second = uniqueRepo();
        set(first, "spike", "tentacle", "Vector spike unmerged.");
        set(second, "tangent", "tentacle", "Hook rewrite paused.");
        set(second, "freeze", "invariant", "API frozen.");

        JsonNode tentacles = json(mockMvc.perform(get("/api/broadcasts")
                                .param("kind", "tentacle")
                                .param("allProjects", "true")
                                .param("limit", "200"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("broadcasts");

        assertThat(tentacles.findValuesAsText("key"))
                .contains("spike", "tangent")
                .doesNotContain("freeze");

        // Fresh tentacles are not stale under the default threshold.
        JsonNode stale = json(mockMvc.perform(get("/api/broadcasts")
                                .param("staleOnly", "true")
                                .param("allProjects", "true"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("broadcasts");
        assertThat(stale.findValuesAsText("key")).doesNotContain("spike", "tangent");
    }

    @Test
    void invalidRequestsAreBadRequests() throws Exception {
        mockMvc.perform(get("/api/broadcasts")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueRepo(), "Not A Slug!", "hazard", "Message.")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"));
        mockMvc.perform(put("/api/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueRepo(), "too-long", "hazard", "x".repeat(281))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recallAttachesTheProjectsBroadcastsWithinTheCap() throws Exception {
        String repo = uniqueRepo();
        set(repo, "stop", "hazard", "Release in progress; do not rebase.");
        set(repo, "thread", "tentacle", "Half-done migration in a worktree.");
        for (int i = 0; i < 9; i++) {
            set(repo, "fact-" + i, "invariant", "Standing fact number " + i + ".");
        }
        set(uniqueRepo(), "elsewhere", "hazard", "Belongs to another project.");

        JsonNode explicit = json(mockMvc.perform(get("/api/recall").param("project", repo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        JsonNode legacy = json(mockMvc.perform(get("/api/recall").param("scope", repo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        JsonNode topic = json(mockMvc.perform(get("/api/recall").param("scope", "release"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        for (JsonNode recall : new JsonNode[] {explicit, legacy}) {
            JsonNode broadcasts = recall.path("broadcasts");
            assertThat(broadcasts.size()).isEqualTo(8);
            assertThat(recall.path("broadcastsOmitted").asInt()).isEqualTo(3);
            assertThat(broadcasts.get(0).path("key").asText()).isEqualTo("stop");
            assertThat(broadcasts.get(1).path("key").asText()).isEqualTo("thread");
            assertThat(broadcasts.findValuesAsText("key")).doesNotContain("elsewhere");
            assertThat(totalChars(broadcasts)).isLessThanOrEqualTo(1_500);
        }
        assertThat(topic.path("broadcasts").size()).isZero();
    }

    @Test
    void mcpToolsSetListClearAndRecallContextCarryBroadcasts() throws Exception {
        String repo = uniqueRepo();

        JsonNode set = json(callback("setBroadcast").call("""
            {"source":"claude","clientSessionId":"s-1","repo":"%s","key":"worktree-live",
             "message":"Do not prune the live worktree.","kind":"hazard","severity":"blocker",
             "ttlHours":null,"followUpAt":null}
            """.formatted(repo)));
        assertThat(set.path("severity").asText()).isEqualTo("blocker");

        JsonNode recall = json(callback("recallContext").call("""
            {"project":"%s","maxChars":500}
            """.formatted(repo)));
        assertThat(recall.path("broadcasts").get(0).path("key").asText()).isEqualTo("worktree-live");

        JsonNode listed = json(callback("listBroadcasts").call("""
            {"project":"%s","kind":null,"staleOnly":null,"allProjects":null}
            """.formatted(repo)));
        assertThat(listed.path("count").asInt()).isEqualTo(1);

        JsonNode cleared = json(callback("clearBroadcast").call("""
            {"source":"claude","clientSessionId":"s-1","repo":"%s","key":"worktree-live","reason":null}
            """.formatted(repo)));
        JsonNode clearedAgain = json(callback("clearBroadcast").call("""
            {"source":"claude","clientSessionId":"s-1","repo":"%s","key":"worktree-live"}
            """.formatted(repo)));
        assertThat(cleared.path("cleared").asBoolean()).isTrue();
        assertThat(clearedAgain.path("cleared").asBoolean()).isFalse();

        JsonNode after = json(callback("recallContext").call("""
            {"project":"%s"}
            """.formatted(repo)));
        assertThat(after.path("broadcasts").size()).isZero();
    }

    private void set(String repo, String key, String kind, String message) throws Exception {
        mockMvc.perform(put("/api/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(repo, key, kind, message)))
                .andExpect(status().isOk());
    }

    private static String body(String repo, String key, String kind, String message) {

        return """
            {"source":"claude","clientSessionId":"s-1","repo":"%s","key":"%s","kind":"%s","message":"%s"}
            """.formatted(repo, key, kind, message);
    }

    private static int totalChars(JsonNode broadcasts) {
        int total = 0;
        for (JsonNode broadcast : broadcasts) {
            total += broadcast.path("key").asText().length()
                    + broadcast.path("message").asText().length();
        }

        return total;
    }

    private static String uniqueRepo() {

        return "/work/broadcast-" + UUID.randomUUID();
    }

    private JsonNode json(String body) throws Exception {

        return objectMapper.readTree(body);
    }

    private ToolCallback callback(String name) {
        for (ToolCallback callback : callbacks.getToolCallbacks()) {
            if (callback.getToolDefinition().name().equals(name)) {

                return callback;
            }
        }

        throw new IllegalArgumentException("Unknown tool: " + name);
    }
}
