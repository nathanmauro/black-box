package dev.nathan.sbaagentic.project;

import dev.nathan.sbaagentic.project.internal.application.ProjectAliasService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        // A temp file DB takes the production WAL + busy_timeout path; cache=shared
        // memory throws SQLITE_LOCKED on writer collisions, ignoring busy_timeout.
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-project-alias-integration-test-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
@AutoConfigureMockMvc
class ProjectAliasIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectAliasService projectAliasService;

    @TempDir
    Path tempDir;

    @Test
    void groupsCountsAndScopesWhileLegacyAliasUrlsRemainValid() throws Exception {
        String key = uniqueKey();
        String primary = "/tmp/primary-" + key;
        String alias = "/tmp/linked-" + key;
        seedEvent("primary-" + key, primary, "Decision", "Primary decision");
        seedEvent("alias-" + key, alias + "/", "AssistantMessage", "Alias output");

        String createdBody = putAlias(alias, primary)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliasKey").value(alias))
                .andExpect(jsonPath("$.canonicalKey").value(primary))
                .andExpect(jsonPath("$.source").value("manual"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(createdBody).path("id").asText()).isNotBlank();

        JsonNode project = project(primary);
        assertThat(project.path("sessionCount").asInt()).isEqualTo(2);
        assertThat(project.path("eventCount").asInt()).isEqualTo(2);
        assertThat(project.path("scopes").size()).isEqualTo(2);
        JsonNode primaryScope = scope(project, primary);
        assertThat(primaryScope.path("primary").asBoolean()).isTrue();
        assertThat(primaryScope.path("source").isNull()).isTrue();
        JsonNode aliasScope = scope(project, alias);
        assertThat(aliasScope.path("primary").asBoolean()).isFalse();
        assertThat(aliasScope.path("source").asText()).isEqualTo("manual");

        String legacyAliasProjectKey = aliasScope.path("projectKey").asText();
        mockMvc.perform(get("/api/projects/{projectKey}/sessions", legacyAliasProjectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/projects/{projectKey}/timeline", legacyAliasProjectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalKey").value(primary))
                .andExpect(jsonPath("$.projectKey").value(project.path("projectKey").asText()))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(delete("/api/project-aliases").param("aliasKey", alias))
                .andExpect(status().isNoContent());
        assertThat(project(primary).path("sessionCount").asInt()).isEqualTo(1);
        assertThat(project(alias).path("sessionCount").asInt()).isEqualTo(1);
        mockMvc.perform(get("/api/projects/{projectKey}/sessions", legacyAliasProjectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aliasWritesAreIdempotentRejectConflictsAndProtectSystemScopes() throws Exception {
        String key = uniqueKey();
        String alias = "/tmp/alias-" + key;
        String primary = "/tmp/primary-" + key;
        String other = "/tmp/other-" + key;

        String first = putAlias(alias, primary)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = putAlias(alias, primary)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(second).path("id").asText())
                .isEqualTo(objectMapper.readTree(first).path("id").asText());

        putAlias(alias, other)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString(primary)));
        putAlias("/", primary).andExpect(status().isBadRequest());
        putAlias("__no_project__", primary).andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/project-aliases").param("aliasKey", alias))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/project-aliases").param("aliasKey", alias))
                .andExpect(status().isNotFound());
    }

    @Test
    void ingestionDiscoversOnlyVerifiedNestedAndGitCommonDirWorktrees() throws Exception {
        String key = uniqueKey();
        String nestedPrimary = tempDir.resolve("nested-primary-" + key).toString();
        String nestedAlias = nestedPrimary + "/.claude/worktrees/focused-worker";
        Files.createDirectories(Path.of(nestedPrimary).resolve(".git"));
        seedEvent("nested-" + key, nestedAlias, "Decision", "Nested worktree decision");
        JsonNode nestedProject = project(nestedPrimary);
        assertThat(nestedProject.path("sessionCount").asInt()).isEqualTo(1);
        assertThat(scope(nestedProject, nestedAlias).path("primary").asBoolean()).isFalse();
        assertThat(scope(nestedProject, nestedAlias).path("source").asText()).isEqualTo("nested-worktree");
        assertThat(aliasSource(nestedAlias)).isEqualTo("nested-worktree");

        Path nearestOwner = tempDir.resolve("outer-" + key)
                .resolve(".worktrees/outer/inner-repository");
        Files.createDirectories(nearestOwner.resolve(".git"));
        String innerWorktree = nearestOwner.resolve(".worktrees/inner").toString();
        seedEvent("nearest-owner-" + key, innerWorktree, "Decision", "Nested repository worktree");
        JsonNode nearestProject = project(nearestOwner.toString());
        assertThat(scope(nearestProject, innerWorktree).path("source").asText()).isEqualTo("nested-worktree");

        String ambiguous = tempDir.resolve(".codex/worktrees/abcd/shared-name").toString();
        seedEvent("ambiguous-" + key, ambiguous, "Decision", "Ambiguous basename");
        assertThat(project(ambiguous).path("sessionCount").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key = ?", Integer.class, ambiguous)).isZero();

        String globalClaudeWorktree = Path.of(System.getProperty("user.home"),
                ".claude", "worktrees", "global-" + key).toString();
        seedEvent("global-claude-" + key, globalClaudeWorktree, "Decision", "Global Claude worktree");
        assertThat(project(globalClaudeWorktree).path("sessionCount").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key = ?",
                Integer.class,
                globalClaudeWorktree)).isZero();

        Path nonRepository = tempDir.resolve("not-a-repository-" + key);
        Files.createDirectories(nonRepository);
        String nonRepositoryWorktree = nonRepository.resolve(".worktrees/worker").toString();
        seedEvent("non-repository-" + key, nonRepositoryWorktree, "Decision", "Non-repository worktree path");
        assertThat(project(nonRepositoryWorktree).path("sessionCount").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key = ?",
                Integer.class,
                nonRepositoryWorktree)).isZero();

        Path owner = tempDir.resolve("owner-" + key);
        Path commonDir = owner.resolve(".git");
        Path gitDir = commonDir.resolve("worktrees/linked");
        Path linked = tempDir.resolve("linked-" + key);
        Files.createDirectories(gitDir);
        Files.createDirectories(linked);
        Files.writeString(gitDir.resolve("commondir"), "../..");
        Files.writeString(linked.resolve(".git"), "gitdir: " + gitDir);

        seedEvent("git-linked-" + key, linked.toString(), "Decision", "Linked Git worktree");
        JsonNode gitProject = project(owner.toString());
        assertThat(gitProject.path("sessionCount").asInt()).isEqualTo(1);
        assertThat(scope(gitProject, linked.toString()).path("primary").asBoolean()).isFalse();
        assertThat(scope(gitProject, linked.toString()).path("source").asText()).isEqualTo("git-commondir");
        assertThat(aliasSource(linked.toString())).isEqualTo("git-commondir");

        Path unrelatedGitDir = commonDir.resolve("metadata/linked");
        Path unrelatedLinked = tempDir.resolve("unrelated-linked-" + key);
        Files.createDirectories(unrelatedGitDir);
        Files.createDirectories(unrelatedLinked);
        Files.writeString(unrelatedGitDir.resolve("commondir"), "../..");
        Files.writeString(unrelatedLinked.resolve(".git"), "gitdir: " + unrelatedGitDir);

        seedEvent("git-unrelated-" + key, unrelatedLinked.toString(), "Decision", "Unverified Git pointer");
        assertThat(project(unrelatedLinked.toString()).path("sessionCount").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key = ?",
                Integer.class,
                unrelatedLinked.toString())).isZero();
    }

    @Test
    void projectReadsStaySideEffectFreeAndStartupDiscoveryBackfillsObservedWorktrees() throws Exception {
        String key = uniqueKey();
        String primary = tempDir.resolve("startup-primary-" + key).toString();
        String alias = primary + "/.worktrees/preexisting";
        Files.createDirectories(Path.of(primary).resolve(".git"));
        jdbcTemplate.update("""
                INSERT INTO agent_sessions
                       (id, source, client_session_id, title, title_rank, cwd,
                        started_at, last_seen_at, event_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "startup-session-" + key, "codex", "startup-client-" + key,
                "Preexisting worktree", 1, alias,
                "2026-07-15T11:00:00Z", "2026-07-15T11:00:00Z", 0);

        assertThat(project(alias).path("sessionCount").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key = ?", Integer.class, alias)).isZero();

        projectAliasService.discoverVerifiedAliases();

        assertThat(project(primary).path("sessionCount").asInt()).isEqualTo(1);
        assertThat(aliasSource(alias)).isEqualTo("nested-worktree");
    }

    @Test
    void discoverySkipsStructuralAliasThatWouldCloseAManualCycle() throws Exception {
        String key = uniqueKey();
        String owner = tempDir.resolve("cycle-owner-" + key).toString();
        String futureWorktree = owner + "/.worktrees/future";
        Files.createDirectories(Path.of(owner).resolve(".git"));
        putAlias(owner, futureWorktree).andExpect(status().isOk());

        seedEvent("cycle-worktree-" + key, futureWorktree, "Decision", "Future worktree evidence");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key = ?",
                Integer.class,
                futureWorktree)).isZero();
        JsonNode project = project(futureWorktree);
        assertThat(project.path("sessionCount").asInt()).isEqualTo(1);
        assertThat(scope(project, owner).path("source").asText()).isEqualTo("manual");

        mockMvc.perform(delete("/api/project-aliases").param("aliasKey", owner))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingManualMergeRestoresStructuralWorktreeGroup() throws Exception {
        String key = uniqueKey();
        String firstPrimary = tempDir.resolve("merge-first-" + key).toString();
        String worktree = firstPrimary + "/.worktrees/worker";
        String secondPrimary = "/tmp/merge-second-" + key;
        Files.createDirectories(Path.of(firstPrimary).resolve(".git"));
        seedEvent("merge-worktree-" + key, worktree, "Decision", "Worktree evidence");
        seedEvent("merge-target-" + key, secondPrimary, "Decision", "Target evidence");

        putAlias(firstPrimary, secondPrimary).andExpect(status().isOk());

        JsonNode merged = project(secondPrimary);
        assertThat(merged.path("sessionCount").asInt()).isEqualTo(2);
        assertThat(scope(merged, firstPrimary).path("source").asText()).isEqualTo("manual");
        assertThat(scope(merged, worktree).path("source").asText()).isEqualTo("nested-worktree");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT canonical_key FROM project_aliases WHERE alias_key = ?",
                String.class,
                worktree)).isEqualTo(firstPrimary);

        mockMvc.perform(delete("/api/project-aliases").param("aliasKey", firstPrimary))
                .andExpect(status().isNoContent());

        JsonNode restored = project(firstPrimary);
        assertThat(restored.path("sessionCount").asInt()).isEqualTo(1);
        assertThat(scope(restored, worktree).path("source").asText()).isEqualTo("nested-worktree");
        assertThat(project(secondPrimary).path("sessionCount").asInt()).isEqualTo(1);
        mockMvc.perform(delete("/api/project-aliases").param("aliasKey", worktree))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentInverseManualWritesNeverPersistACycle() throws Exception {
        String key = uniqueKey();
        String first = "/tmp/concurrent-first-" + key;
        String second = "/tmp/concurrent-second-" + key;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Throwable> failures = runConcurrently(
                () -> projectAliasService.put(new ProjectAliasRequest(first, second)),
                () -> projectAliasService.put(new ProjectAliasRequest(second, first)),
                ready,
                start);

        assertThat(failures).filteredOn(failure -> failure != null).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key IN (?, ?)",
                Integer.class,
                first,
                second)).isEqualTo(1);
        assertThat(projectAliasService.resolve(first)).isEqualTo(projectAliasService.resolve(second));
    }

    @Test
    void concurrentDiscoveryAndManualInverseNeverPersistACycle() throws Exception {
        String key = uniqueKey();
        String owner = tempDir.resolve("concurrent-owner-" + key).toString();
        String worktree = owner + "/.worktrees/worker";
        Files.createDirectories(Path.of(owner).resolve(".git"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        runConcurrently(
                () -> projectAliasService.discoverVerifiedAlias(worktree),
                () -> projectAliasService.put(new ProjectAliasRequest(owner, worktree)),
                ready,
                start);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_aliases WHERE alias_key IN (?, ?)",
                Integer.class,
                owner,
                worktree)).isEqualTo(1);
        assertThat(projectAliasService.resolve(owner)).isEqualTo(projectAliasService.resolve(worktree));
    }

    @Test
    void meldPreviewSaveListingAndTimelineResolveAliasScopes() throws Exception {
        String key = uniqueKey();
        String primary = "/tmp/meld-primary-" + key;
        String alias = "/tmp/meld-alias-" + key;
        seedEvent("meld-primary-" + key, primary, "Decision", "Primary meld evidence");
        seedEvent("meld-alias-" + key, alias, "Decision", "Alias meld evidence");
        putAlias(alias, primary).andExpect(status().isOk());

        JsonNode project = project(primary);
        String primaryProjectKey = project.path("projectKey").asText();
        String aliasProjectKey = scope(project, alias).path("projectKey").asText();
        JsonNode sessions = objectMapper.readTree(mockMvc.perform(
                        get("/api/projects/{projectKey}/sessions", aliasProjectKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> sessionIds = textValues(sessions, "id");

        String historicalMeldId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO session_melds
                       (id, project_key, title, body, provider, model, prompt_version,
                        execution_mode, saved_from_preview, metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, historicalMeldId, alias, "Historical alias meld", "Historical body", "local",
                "context-bundle", "v1", "export_bundle", 1, null, "2026-07-15T12:05:00Z");

        mockMvc.perform(post("/api/projects/{projectKey}/melds/preview", aliasProjectKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionIds", sessionIds,
                                "executionMode", "export_bundle"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalKey").value(primary))
                .andExpect(jsonPath("$.projectKey").value(primaryProjectKey))
                .andExpect(jsonPath("$.sessionCount").value(2));

        mockMvc.perform(post("/api/melds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectKey", aliasProjectKey,
                                "title", "New canonical meld",
                                "body", "Canonical body",
                                "provider", "local",
                                "model", "context-bundle",
                                "executionMode", "export_bundle",
                                "savedFromPreview", true,
                                "sessionIds", sessionIds))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalKey").value(primary))
                .andExpect(jsonPath("$.projectKey").value(primaryProjectKey));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_melds WHERE project_key = ?", Integer.class, primary)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_melds WHERE project_key = ?", Integer.class, alias)).isEqualTo(1);
        mockMvc.perform(get("/api/projects/{projectKey}/melds", primaryProjectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].canonicalKey").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(primary))));

        JsonNode timeline = objectMapper.readTree(mockMvc.perform(
                        get("/api/projects/{projectKey}/timeline", primaryProjectKey).param("limit", "20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(textValues(timeline.path("items"), "sourceType").stream()
                .filter("saved_meld"::equals)
                .count()).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions putAlias(String alias, String canonical) throws Exception {
        return mockMvc.perform(put("/api/project-aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "aliasKey", alias,
                        "canonicalKey", canonical))));
    }

    private void seedEvent(String clientSessionId, String cwd, String eventType, String text) throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "source", "codex",
                                "clientSessionId", clientSessionId,
                                "eventType", eventType,
                                "role", "assistant",
                                "text", text,
                                "cwd", cwd,
                                "observedAt", "2026-07-15T12:00:00Z"))))
                .andExpect(status().isOk());
    }

    private JsonNode project(String canonicalKey) throws Exception {
        JsonNode projects = objectMapper.readTree(mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode project : projects) {
            if (canonicalKey.equals(project.path("canonicalKey").asText())) {
                return project;
            }
        }
        throw new AssertionError("Missing project " + canonicalKey);
    }

    private static JsonNode scope(JsonNode project, String canonicalKey) {
        for (JsonNode scope : project.path("scopes")) {
            if (canonicalKey.equals(scope.path("canonicalKey").asText())) {
                return scope;
            }
        }
        throw new AssertionError("Missing scope " + canonicalKey);
    }

    private String aliasSource(String aliasKey) {
        return jdbcTemplate.queryForObject(
                "SELECT source FROM project_aliases WHERE alias_key = ?", String.class, aliasKey);
    }

    private static List<String> textValues(JsonNode items, String field) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            values.add(item.path(field).asText());
        }
        return values;
    }

    private static List<Throwable> runConcurrently(
            ThrowingOperation first,
            ThrowingOperation second,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstResult = executor.submit(() -> runAfterSignal(first, ready, start));
            Future<Throwable> secondResult = executor.submit(() -> runAfterSignal(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.Arrays.asList(
                    firstResult.get(5, TimeUnit.SECONDS),
                    secondResult.get(5, TimeUnit.SECONDS));
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static Throwable runAfterSignal(
            ThrowingOperation operation,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return new AssertionError("Timed out waiting to start concurrent alias write");
            }
            operation.run();
            return null;
        }
        catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static String uniqueKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
