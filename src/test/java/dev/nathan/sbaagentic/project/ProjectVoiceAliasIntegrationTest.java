package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.project.internal.application.ProjectAliasService;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-voice-alias-test-${random.uuid}.db",
            "sba.projects.voice.canonical.scope=${user.home}/Documents/Codex/2026-09-15/realtime-voice-chat",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class ProjectVoiceAliasIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProjectAliasService aliases;

    private final String root =
            Path.of(System.getProperty("user.home"), "Documents", "Codex").toString();
    private final String canonical = root + "/2026-09-15/realtime-voice-chat";

    @Test
    void historicalAndNewVoiceSessionsGroupWithoutChangingRawProvenance() throws Exception {
        String historical = root + "/2026-09-22/realtime-voice-chat-2";
        String newVoice = root + "/2026-09-23-new-realtime-voice-chat";
        String unrelated = root + "/2026-09-24/other-project";
        seed(historical, "historical");
        seed(newVoice, "new voice");
        seed(unrelated, "unrelated");

        // Simulate startup backfill on a scope already present before the resolver was enabled.
        String older = root + "/2026-09-20/realtime-voice-chat";
        jdbc.update(
                "INSERT INTO agent_sessions (id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                "codex",
                UUID.randomUUID().toString(),
                "Historical voice",
                older,
                "2026-09-20T12:00:00Z",
                "2026-09-20T12:00:00Z",
                0);
        aliases.discoverVerifiedAliases();

        assertThat(aliases.resolve(historical)).isEqualTo(canonical);
        assertThat(aliases.resolve(newVoice)).isEqualTo(canonical);
        assertThat(aliases.resolve(older)).isEqualTo(canonical);
        assertThat(aliases.resolve(unrelated)).isEqualTo(unrelated);
        JsonNode projects = mapper.readTree(mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        JsonNode voice = find(projects, canonical);
        assertThat(voice.path("sessionCount").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(voice.path("eventCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(voice.path("scopes").toString()).contains(historical, newVoice, older);
        assertThat(find(projects, unrelated).path("eventCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT cwd FROM agent_sessions WHERE cwd IN (?, ?)", String.class, historical, newVoice))
                .containsExactlyInAnyOrder(historical, newVoice);

        JsonNode page = mapper.readTree(mvc.perform(get("/api/events")
                        .param("q", "project_group:\"" + canonical + "\"")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(page.path("items").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void onlyExactVoiceDirectoriesAreDiscoveredAndManualMappingWins() throws Exception {
        String[] rejected = {
            root + "/2026-09-23/realtime-voice-chat-extra",
            root + "/2026-09-23/realtime-voice-chat/child",
            root + "/2026-02-30/realtime-voice-chat",
            root + "/2026-09-23/realtime-voice-chat-0",
            "constellate"
        };
        for (String scope : rejected) {
            aliases.discoverVerifiedAlias(scope);
            assertThat(aliases.resolve(scope)).isEqualTo(scope);
        }
        String explicit = root + "/2026-09-25/realtime-voice-chat";
        String other = root + "/some-other-owner";
        aliases.put(new ProjectAliasRequest(explicit, other));
        aliases.discoverVerifiedAlias(explicit);
        assertThat(aliases.resolve(explicit)).isEqualTo(other);
    }

    @Test
    void automaticVoiceAliasCanBeRemovedWithoutChangingTheRecordedSession() throws Exception {
        String scope = root + "/2026-09-26/realtime-voice-chat";
        seed(scope, "reversible alias");
        assertThat(aliases.resolve(scope)).isEqualTo(canonical);
        mvc.perform(delete("/api/project-aliases").param("aliasKey", scope)).andExpect(status().isNoContent());
        assertThat(aliases.resolve(scope)).isEqualTo(scope);
        assertThat(jdbc.queryForObject("SELECT cwd FROM agent_sessions WHERE cwd = ?", String.class, scope))
                .isEqualTo(scope);
        // With the configuration still enabled, discovery deliberately restores the grouping.
        aliases.discoverVerifiedAlias(scope);
        assertThat(aliases.resolve(scope)).isEqualTo(canonical);
    }

    private void seed(String cwd, String text) throws Exception {
        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "source",
                                "codex",
                                "clientSessionId",
                                UUID.randomUUID().toString(),
                                "eventType",
                                "Decision",
                                "role",
                                "assistant",
                                "text",
                                text,
                                "cwd",
                                cwd,
                                "observedAt",
                                "2026-09-23T12:00:00Z"))))
                .andExpect(status().isOk());
    }

    private static JsonNode find(JsonNode projects, String key) {
        for (JsonNode project : projects) {
            if (key.equals(project.path("canonicalKey").asText()))

                return project;
        }
        throw new AssertionError("Missing project " + key);
    }
}
