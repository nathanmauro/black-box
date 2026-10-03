package dev.nathan.sbaagentic.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite.RetiredWorkflowSchemaMigration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "sba.storage.retire-workflow=true",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class RetiredWorkflowUpgradeApiTest {
    private static final String URL =
            "jdbc:sqlite:" + System.getProperty("java.io.tmpdir") + "/bb-retired-workflow-" + UUID.randomUUID() + ".db";
    private static final String TIMESTAMP = Instant.now().toString();
    private static final String METADATA = """
        {"kind":"handoff","contextSummary":"Verified task completion","openLoops":["Review output"],"nextAction":"Continue","repo":"/repo/legacy"}
        """.strip();

    @DynamicPropertySource
    static void legacyDatabase(DynamicPropertyRegistry properties) {
        var source = new DriverManagerDataSource(URL);
        new ResourceDatabasePopulator(new ClassPathResource("contracts/pre-task-retirement.sqlite.sql"))
                .execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO agent_sessions (id,source,client_session_id,title,cwd,started_at,last_seen_at,event_count) VALUES ('legacy-parent','codex','completion-client','Task completion','/repo/legacy',?,?,1)",
                TIMESTAMP,
                TIMESTAMP);
        jdbc.update(
                "INSERT INTO agent_events (id,session_id,source,client_session_id,event_type,role,text,metadata_json,observed_at) VALUES ('completion-handoff','legacy-parent','codex','completion-client','Handoff','assistant',?,?,?)",
                "Verified task completion\n\nOpen loops:\n- Review output\n\nNext: Continue",
                METADATA,
                TIMESTAMP);
        jdbc.update(
                "INSERT INTO specs VALUES ('spec','/repo/legacy','Frozen spec','body',NULL,'active','planner',?,?)",
                TIMESTAMP,
                TIMESTAMP);
        jdbc.update(
                "INSERT INTO tasks VALUES ('task','spec','/repo/legacy','Completed task','codex','done',1,'planner','worker',NULL,'completion-handoff',?,?)",
                TIMESTAMP,
                TIMESTAMP);
        jdbc.update(
                "INSERT INTO task_events VALUES ('transition','task','task.completed','worker','in_progress','done','{}',?)",
                TIMESTAMP);
        jdbc.update(
                "INSERT INTO session_links VALUES ('linked','legacy-parent','child','spawned','task',?), ('unassociated','legacy-parent','other-child','continued',NULL,?)",
                TIMESTAMP,
                TIMESTAMP);
        properties.add("spring.datasource.url", () -> URL);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RetiredWorkflowSchemaMigration migration;

    @Test
    void upgradePreservesTaskCompletionHandoffRecallAndSessionLineage() throws Exception {
        migration.migrate(); // repeat after the first startup migration
        assertThat(jdbc.queryForObject(
                        "SELECT metadata_json FROM agent_events WHERE id='completion-handoff'", String.class))
                .isEqualTo(METADATA);
        assertThat(jdbc.queryForObject("SELECT event_count FROM agent_sessions WHERE id='legacy-parent'", Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('specs','tasks','task_events')"))
                .isEmpty();
        assertThat(jdbc.queryForList("PRAGMA table_info(session_links)"))
                .noneMatch(c -> "task_id".equals(c.get("name")));
        assertThat(jdbc.queryForList("SELECT id FROM session_links ORDER BY id", String.class))
                .containsExactly("linked", "unassociated");
        assertThat(jdbc.queryForObject("SELECT created_at FROM session_links WHERE id='linked'", String.class))
                .isEqualTo(TIMESTAMP);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        mvc.perform(get("/api/recall").param("scope", "completion-handoff").param("kinds", "handoff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventId").value("completion-handoff"))
                .andExpect(jsonPath("$.items[0].kind").value("handoff"))
                .andExpect(jsonPath("$.items[0].headline").value("Verified task completion"))
                .andExpect(jsonPath("$.items[0].nextAction").value("Continue"));
        mvc.perform(get("/api/sessions/child/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parents[0].linkId").value("linked"))
                .andExpect(jsonPath("$.parents[0].session.title").value("Task completion"))
                .andExpect(jsonPath("$.parents[0].taskId").doesNotExist());
        mvc.perform(get("/api/session-links/child-counts").param("ids", "legacy-parent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['legacy-parent']").value(2));
        mvc.perform(get("/api/dag").param("sessionId", "legacy-parent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(3));
        mvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"parentSessionId":"legacy-parent","childSessionId":"new-child","linkType":"spawned"}
                    """))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks")).andExpect(status().isNotFound());
        mvc.perform(get("/api/specs/spec")).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/task/dag")).andExpect(status().isNotFound());
    }
}
