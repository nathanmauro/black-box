package dev.nathan.sbaagentic.project.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-continuity-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.judge.enabled=false",
            // A credential-named setting: its value is masked everywhere and must never surface.
            "sba.judge.api-key=" + ContinuityControllerTest.CONFIGURED_SECRET
        })
@AutoConfigureMockMvc
@DisplayName("Continuity lint and braid-graph export are read-only, bounded and redacted")
class ContinuityControllerTest {

    static final String CONFIGURED_SECRET = "Zq7Wm3Lp9Tx4Rv8Nc2Hb6Kd";
    private static final String CAPTURED_PASSWORD = "c0ntinuity-hunter2-Secret";
    private static final String REPO = "/fixture/continuity";
    private static final String OTHER_REPO = "/fixture/elsewhere";

    /** One generated Mermaid statement per line; labels may hold only allowlisted text and entities. */
    private static final String LABEL = "[A-Za-z0-9 _.,:/\\-]*(?:#\\d+;[A-Za-z0-9 _.,:/\\-]*)*";

    private static final Pattern MERMAID_LINE = Pattern.compile(String.join(
            "|",
            "flowchart LR",
            "  %% Black Box braid graph: \\d+ nodes, \\d+ edges(, truncated)?\\. Labels are redacted\\.",
            "  classDef (session|decision|handoff|braid|missing_session) [a-z0-9:#,\\- ]+",
            "  s\\d+\\[\"" + LABEL + "\"\\]:::session",
            "  d\\d+\\{\\{\"" + LABEL + "\"\\}\\}:::decision",
            "  h\\d+\\(\\[\"" + LABEL + "\"\\]\\):::handoff",
            "  b\\d+\\(\\(\"" + LABEL + "\"\\)\\):::braid",
            "  x\\d+\\[\"" + LABEL + "\"\\]:::missing_session",
            "  [sdhbx]\\d+ -->\\|(decided|handed_off|carried_by|picked_up_by|woven_into)\\| [sdhbx]\\d+"));

    @Autowired
    MockMvc http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private int sequence;

    @BeforeEach
    void emptyStore() {
        jdbc.update("DELETE FROM session_meld_inputs");
        jdbc.update("DELETE FROM session_melds");
        jdbc.update("DELETE FROM project_aliases");
        jdbc.update("DELETE FROM agent_events");
        jdbc.update("DELETE FROM agent_sessions");
    }

    // ------------------------------------------------------------------------------- empty store

    @Test
    void emptyDatabaseYieldsEmptyReportsInEveryFormat() throws Exception {
        http.perform(get("/api/diagnostics/continuity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingCount").value(0))
                .andExpect(jsonPath("$.findings").isEmpty())
                .andExpect(jsonPath("$.findingsTruncated").value(false))
                .andExpect(jsonPath("$.staleAfterHours").value(168))
                .andExpect(jsonPath("$.scan.decisions").value(0))
                .andExpect(jsonPath("$.scan.braids").value(0))
                .andExpect(jsonPath("$.scan.truncated").value(false))
                .andExpect(jsonPath("$.ruleCounts.orphan_braid").value(0))
                .andExpect(jsonPath("$.ruleCounts.stale_handoff").value(0))
                .andExpect(jsonPath("$.ruleCounts.decision_missing_kind").value(0));

        http.perform(get("/api/export/braid-graph"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.nodes").isEmpty())
                .andExpect(jsonPath("$.edges").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.counts.sessions").value(0));

        String mermaid = mermaid("/api/export/braid-graph?format=mermaid");
        assertThat(mermaid).startsWith("flowchart LR\n").contains("0 nodes, 0 edges");
        assertGrammar(mermaid);
    }

    // ------------------------------------------------------------------------------------- lint

    @Test
    void lintFlagsStaleHandoffsMissingMetadataOrphanBraidsAndUnscopedCaptures() throws Exception {
        Instant now = Instant.now();
        session("s-old", "codex", "Old work", REPO, now.minus(30, ChronoUnit.DAYS));
        session("s-new", "claude", "New work", REPO, now.minus(2, ChronoUnit.DAYS));
        session("s-other", "codex", "Other repo", OTHER_REPO, now.minus(20, ChronoUnit.DAYS));
        session("s-loose", "codex", "No project", null, now.minus(1, ChronoUnit.DAYS));

        // Answered: a decision in the same project came later.
        handoff("h-answered", "s-old", REPO, "Answered handoff", now.minus(29, ChronoUnit.DAYS));
        decision(
                "d-clean",
                "s-new",
                REPO,
                "Clean decision",
                "Because.\nKind: architecture | Tests: none",
                List.of("rejected option"),
                now.minus(2, ChronoUnit.DAYS));
        // Stale: old, and nothing in its project since.
        handoff("h-stale", "s-other", OTHER_REPO, "Nobody picked this up", now.minus(19, ChronoUnit.DAYS));
        // Recent: not stale yet, though unanswered.
        handoff("h-recent", "s-new", REPO, "Fresh handoff", now.minus(30, ChronoUnit.MINUTES));
        decision(
                "d-bare", "s-new", REPO, "Bare decision", "No trailer here", List.of(), now.minus(3, ChronoUnit.HOURS));
        decision("d-kind-only", "s-new", REPO, "Has kind", "kind: data", List.of(" "), now.minus(4, ChronoUnit.HOURS));
        observation("o-loose", "s-loose", null, "Unscoped fact", now.minus(5, ChronoUnit.HOURS));
        observation("o-scoped", "s-new", REPO, "Scoped fact", now.minus(6, ChronoUnit.HOURS));

        braid("braid-orphan", null, "Orphan braid", now.minus(5, ChronoUnit.DAYS), "gone-1", "gone-2");
        braid("braid-partial", REPO, "Partial braid", now.minus(4, ChronoUnit.DAYS), "s-old", "gone-3");
        braid("braid-single", REPO, "Single braid", now.minus(3, ChronoUnit.DAYS), "s-new");
        braid("braid-healthy", REPO, "Healthy braid", now.minus(2, ChronoUnit.DAYS), "s-old", "s-new");
        braid("braid-empty", null, "Empty braid", now.minus(1, ChronoUnit.DAYS));

        JsonNode report = getJson("/api/diagnostics/continuity");

        assertThat(report.path("ruleCounts").path("stale_handoff").asInt()).isEqualTo(1);
        assertThat(report.path("ruleCounts").path("decision_missing_kind").asInt())
                .isEqualTo(1);
        assertThat(report.path("ruleCounts")
                        .path("decision_missing_alternatives")
                        .asInt())
                .isEqualTo(2);
        assertThat(report.path("ruleCounts").path("orphan_braid").asInt()).isEqualTo(2);
        assertThat(report.path("ruleCounts").path("braid_missing_sessions").asInt())
                .isEqualTo(1);
        assertThat(report.path("ruleCounts").path("undersized_braid").asInt()).isEqualTo(1);
        assertThat(report.path("ruleCounts").path("unscoped_capture").asInt()).isEqualTo(1);
        assertThat(report.path("findingCount").asInt()).isEqualTo(9);
        assertThat(report.path("scan").path("decisions").asInt()).isEqualTo(3);
        assertThat(report.path("scan").path("handoffs").asInt()).isEqualTo(3);
        assertThat(report.path("scan").path("observations").asInt()).isEqualTo(2);
        assertThat(report.path("scan").path("braids").asInt()).isEqualTo(5);

        assertThat(subjects(report, "stale_handoff")).containsExactly("h-stale");
        assertThat(subjects(report, "decision_missing_kind")).containsExactly("d-bare");
        assertThat(subjects(report, "decision_missing_alternatives"))
                .containsExactlyInAnyOrder("d-bare", "d-kind-only");
        assertThat(subjects(report, "orphan_braid")).containsExactlyInAnyOrder("braid-orphan", "braid-empty");
        assertThat(subjects(report, "braid_missing_sessions")).containsExactly("braid-partial");
        assertThat(subjects(report, "undersized_braid")).containsExactly("braid-single");
        assertThat(subjects(report, "unscoped_capture")).containsExactly("o-loose");

        JsonNode first = report.path("findings").get(0);
        assertThat(first.path("severity").asText()).isEqualTo("error");
        JsonNode stale = finding(report, "stale_handoff");
        assertThat(stale.path("project").asText()).isEqualTo(OTHER_REPO);
        assertThat(stale.path("sessionId").asText()).isEqualTo("s-other");
        assertThat(stale.path("excerpt").asText()).isEqualTo("Nobody picked this up");
        assertThat(stale.path("detail").asText()).contains("No decision or handoff");

        // A one-hour window still spares the 30-minute-old handoff and the answered one.
        JsonNode tight = getJson("/api/diagnostics/continuity?staleAfterHours=1&limit=2");
        assertThat(subjects(tight, "stale_handoff")).isEmpty();
        assertThat(tight.path("ruleCounts").path("stale_handoff").asInt()).isEqualTo(1);
        assertThat(tight.path("findings")).hasSize(2);
        assertThat(tight.path("findingsTruncated").asBoolean()).isTrue();
    }

    @Test
    void lintScopesToOneProjectThroughItsAliases() throws Exception {
        Instant now = Instant.now();
        session("s-a", "codex", "A", REPO, now.minus(10, ChronoUnit.DAYS));
        session("s-b", "codex", "B", OTHER_REPO, now.minus(10, ChronoUnit.DAYS));
        session("s-alias", "codex", "Alias", "/fixture/continuity-old", now.minus(9, ChronoUnit.DAYS));
        decision("d-a", "s-a", REPO, "In project", "no trailer", List.of(), now.minus(10, ChronoUnit.DAYS));
        decision("d-b", "s-b", OTHER_REPO, "Elsewhere", "no trailer", List.of(), now.minus(10, ChronoUnit.DAYS));
        decision(
                "d-alias",
                "s-alias",
                "/fixture/continuity-old",
                "Via alias",
                "x",
                List.of(),
                now.minus(9, ChronoUnit.DAYS));
        braid("braid-b", OTHER_REPO, "Other braid", now.minus(1, ChronoUnit.DAYS), "s-b");
        jdbc.update(
                "INSERT INTO project_aliases (id, alias_key, canonical_key, source, created_at) VALUES (?,?,?,?,?)",
                "alias-1",
                "/fixture/continuity-old",
                REPO,
                "test",
                now.toString());

        JsonNode report = getJson("/api/diagnostics/continuity?project=" + REPO + "/");

        assertThat(report.path("project").asText()).isEqualTo(REPO);
        assertThat(subjects(report, "decision_missing_kind")).containsExactlyInAnyOrder("d-a", "d-alias");
        assertThat(subjects(report, "undersized_braid")).isEmpty();
        assertThat(report.path("scan").path("decisions").asInt()).isEqualTo(2);
    }

    // ----------------------------------------------------------------------------------- graph

    @Test
    void graphLinksSessionsDecisionsHandoffsAndBraids() throws Exception {
        Instant t0 = Instant.now().minus(10, ChronoUnit.DAYS);
        session("s-1", "codex", "First", REPO, t0);
        session("s-2", "claude", "Second", REPO, t0.plus(2, ChronoUnit.DAYS));
        decision("d-1", "s-1", REPO, "Pick SQLite", "Kind: storage", List.of("postgres"), t0.plus(1, ChronoUnit.HOURS));
        handoff("h-1", "s-1", REPO, "Carry on with export", t0.plus(2, ChronoUnit.HOURS));
        decision("d-2", "s-2", REPO, "Ship export", "Kind: api", List.of("none"), t0.plus(3, ChronoUnit.DAYS));
        braid("braid-1", REPO, "Weave both", t0.plus(4, ChronoUnit.DAYS), "s-1", "s-2", "gone-9");

        JsonNode graph = getJson("/api/export/braid-graph?format=json&project=" + REPO);

        assertThat(graph.path("counts").path("sessions").asInt()).isEqualTo(2);
        assertThat(graph.path("counts").path("decisions").asInt()).isEqualTo(2);
        assertThat(graph.path("counts").path("handoffs").asInt()).isEqualTo(1);
        assertThat(graph.path("counts").path("braids").asInt()).isEqualTo(1);
        assertThat(graph.path("counts").path("missingSessions").asInt()).isEqualTo(1);
        assertThat(graph.path("truncated").asBoolean()).isFalse();

        Map<String, String> ids = nodeIdsByRef(graph);
        assertThat(edges(graph))
                .containsExactlyInAnyOrder(
                        ids.get("s-1") + " decided " + ids.get("d-1"),
                        ids.get("s-1") + " handed_off " + ids.get("h-1"),
                        ids.get("d-1") + " carried_by " + ids.get("h-1"),
                        ids.get("s-2") + " decided " + ids.get("d-2"),
                        ids.get("h-1") + " picked_up_by " + ids.get("s-2"),
                        ids.get("s-1") + " woven_into " + ids.get("braid-1"),
                        ids.get("s-2") + " woven_into " + ids.get("braid-1"),
                        ids.get("gone-9") + " woven_into " + ids.get("braid-1"));
        assertThat(ids.get("s-1")).isEqualTo("s1");
        assertThat(ids.get("gone-9")).isEqualTo("x1");
        JsonNode braidNode = node(graph, "braid-1");
        assertThat(braidNode.path("flags").get(0).asText()).isEqualTo("missing_sessions");
        assertThat(node(graph, "d-1").path("label").asText()).isEqualTo("Pick SQLite");

        String mermaid = mermaid("/api/export/braid-graph?format=MERMAID&project=" + REPO);
        assertGrammar(mermaid);
        assertThat(mermaid).contains("  s1[\"codex - First\"]:::session").contains("  h1 -->|picked_up_by| s2");
    }

    @Test
    void mermaidStaysWellFormedWhateverTheStoredTextHolds() throws Exception {
        List<String> hostile = List.of(
                "\"]:::braid\n  click s1 href \"javascript:alert(1)\"",
                "%%{init: {'securityLevel': 'loose'}}%%",
                "a]; b[x",
                "close}} then ((open",
                "pipe | in -->|label| s9",
                "<img src=x onerror=alert(1)>",
                "`**markdown**` & #quot; entity",
                "line one\r\nline two three\u0000four",
                "rtl ‮evil‬ and zero​width",
                "emoji 🚀 and accents é");
        Instant t0 = Instant.now().minus(1, ChronoUnit.DAYS);
        List<String> sessions = new ArrayList<>();
        for (int i = 0; i < hostile.size(); i++) {
            String id = "hostile-" + i;
            sessions.add(id);
            session(id, "codex", hostile.get(i), REPO, t0.plusSeconds(i));
            decision(
                    "hd-" + i,
                    id,
                    REPO,
                    hostile.get(i),
                    hostile.get(i),
                    List.of(hostile.get(i)),
                    t0.plusSeconds(100 + i));
            handoff("hh-" + i, id, REPO, hostile.get(i), t0.plusSeconds(200 + i));
        }
        braid(
                "hostile-braid",
                REPO,
                hostile.get(0),
                t0.plusSeconds(300),
                sessions.subList(0, 3).toArray(String[]::new));

        String mermaid = mermaid("/api/export/braid-graph?format=mermaid");

        assertGrammar(mermaid);
        assertThat(mermaid.lines().filter(l -> l.startsWith("  %%"))).hasSize(1);
        assertThat(mermaid).doesNotContain("%%{").doesNotContain("<img");
        // The original text survives as entities: the label still says what was stored.
        assertThat(decodeEntities(mermaid)).contains("<img src=x onerror=alert(1)>");
    }

    // --------------------------------------------------------------------------------- secrets

    @Test
    void configuredBase64AndCapturedSecretsNeverLeakInAnyFormat() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(CONFIGURED_SECRET.getBytes(StandardCharsets.UTF_8));
        String embedded = Base64.getEncoder()
                .encodeToString(("svc:" + CONFIGURED_SECRET + ":tail").getBytes(StandardCharsets.UTF_8));
        String urlSafe = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("k=" + CONFIGURED_SECRET).getBytes(StandardCharsets.UTF_8));
        List<String> leaks = List.of(
                CONFIGURED_SECRET,
                "b64 " + base64,
                "stream " + embedded + " end",
                "url " + urlSafe,
                "login password=" + CAPTURED_PASSWORD);
        String secretRepo = "/fixture/" + CONFIGURED_SECRET;
        Instant t0 = Instant.now().minus(30, ChronoUnit.DAYS);
        for (int i = 0; i < leaks.size(); i++) {
            String leak = leaks.get(i);
            String session = "leak-session-" + i;
            session(session, "codex", "title " + leak, i == 0 ? secretRepo : REPO, t0.plusSeconds(i));
            decision(
                    "leak-d-" + i,
                    session,
                    i == 0 ? secretRepo : REPO,
                    leak,
                    "why " + leak,
                    List.of(),
                    t0.plusSeconds(10 + i));
            handoff("leak-h-" + i, session, i == 0 ? secretRepo : REPO, leak, t0.plusSeconds(20 + i));
            observation("leak-o-" + i, session, null, leak, t0.plusSeconds(30 + i));
            braid(
                    "leak-braid-" + i,
                    i == 0 ? secretRepo : null,
                    "braid " + leak,
                    t0.plusSeconds(40 + i),
                    session,
                    "gone-" + i);
        }

        List<String> bodies = List.of(
                body("/api/diagnostics/continuity?staleAfterHours=1&limit=1000"),
                body("/api/diagnostics/continuity?project=" + secretRepo),
                body("/api/export/braid-graph"),
                body("/api/export/braid-graph?project=" + secretRepo),
                decodeEntities(body("/api/export/braid-graph?format=mermaid")));

        for (String body : bodies) {
            assertThat(body).doesNotContain(CONFIGURED_SECRET, base64, embedded, urlSafe, CAPTURED_PASSWORD);
            assertThat(decodableSecret(body))
                    .as("a Base64 run still decodes to the secret")
                    .isFalse();
        }
        // The rows were found and reported, only masked: this is not an empty-result pass.
        JsonNode report = json.readTree(bodies.get(0));
        assertThat(report.path("scan").path("decisions").asInt()).isEqualTo(leaks.size());
        assertThat(report.path("ruleCounts").path("stale_handoff").asInt()).isGreaterThan(0);
        JsonNode graph = json.readTree(bodies.get(2));
        assertThat(graph.path("counts").path("braids").asInt()).isEqualTo(leaks.size());
    }

    // ------------------------------------------------------------------------------ validation

    @Test
    void invalidParametersAreRejected() throws Exception {
        http.perform(get("/api/export/braid-graph?format=svg")).andExpect(status().isBadRequest());
        http.perform(get("/api/export/braid-graph?maxSessions=0")).andExpect(status().isBadRequest());
        http.perform(get("/api/export/braid-graph?maxSessions=201")).andExpect(status().isBadRequest());
        http.perform(get("/api/diagnostics/continuity?staleAfterHours=0")).andExpect(status().isBadRequest());
        http.perform(get("/api/diagnostics/continuity?staleAfterHours=8761")).andExpect(status().isBadRequest());
        http.perform(get("/api/diagnostics/continuity?limit=1001")).andExpect(status().isBadRequest());
        http.perform(get("/api/diagnostics/continuity?limit=abc")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------- scale

    @Test
    void largeSyntheticBraidsStayBoundedWellFormedAndFast() throws Exception {
        Instant t0 = Instant.now().minus(60, ChronoUnit.DAYS);
        int sessions = 300;
        List<Object[]> sessionRows = new ArrayList<>();
        List<Object[]> eventRows = new ArrayList<>();
        for (int s = 0; s < sessions; s++) {
            String id = "bulk-s-" + s;
            String repo = "/fixture/bulk-" + (s % 5);
            sessionRows.add(new Object[] {
                id,
                "codex",
                "client-" + id,
                "Bulk session " + s,
                repo,
                t0.plusSeconds(s * 600L).toString(),
                t0.plusSeconds(s * 600L).toString()
            });
            for (int c = 0; c < 30; c++) {
                boolean handoff = c % 3 == 2;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("kind", handoff ? "handoff" : "decision");
                metadata.put(handoff ? "contextSummary" : "decision", "Bulk " + s + "/" + c + " \"q\" ]");
                metadata.put("repo", repo);
                eventRows.add(new Object[] {
                    "bulk-e-" + s + "-" + c,
                    id,
                    "codex",
                    "client-" + id,
                    handoff ? "Handoff" : "Decision",
                    "text",
                    json.writeValueAsString(metadata),
                    t0.plusSeconds(s * 600L + c).toString()
                });
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO agent_sessions (id, source, client_session_id, title, cwd, started_at, last_seen_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                sessionRows);
        jdbc.batchUpdate(
                "INSERT INTO agent_events (id, session_id, source, client_session_id, event_type, role, text,"
                        + " metadata_json, observed_at) VALUES (?,?,?,?,?,'assistant',?,?,?)",
                eventRows);
        for (int b = 0; b < 250; b++) {
            String[] inputs = new String[8];
            for (int i = 0; i < 8; i++) {
                inputs[i] = i == 7 && b % 10 == 0 ? "bulk-gone-" + b : "bulk-s-" + ((b * 7 + i) % sessions);
            }
            braid("bulk-braid-" + b, null, "Bulk braid " + b, t0.plusSeconds(b * 60L), inputs);
        }

        long start = System.nanoTime();
        JsonNode graph = getJson("/api/export/braid-graph?maxSessions=200");
        String mermaid = mermaid("/api/export/braid-graph?format=mermaid&maxSessions=200");
        JsonNode report = getJson("/api/diagnostics/continuity?limit=1000");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(graph.path("truncated").asBoolean()).isTrue();
        assertThat(graph.path("counts").path("sessions").asInt()).isEqualTo(200);
        assertThat(graph.path("counts").path("braids").asInt()).isLessThanOrEqualTo(200);
        assertThat(graph.path("counts").path("decisions").asInt()
                        + graph.path("counts").path("handoffs").asInt())
                .isEqualTo(200 * 25);
        assertThat(graph.path("nodes").size())
                .isEqualTo(graph.path("counts").path("sessions").asInt()
                        + graph.path("counts").path("decisions").asInt()
                        + graph.path("counts").path("handoffs").asInt()
                        + graph.path("counts").path("braids").asInt()
                        + graph.path("counts").path("missingSessions").asInt());
        assertEdgesReferenceNodes(graph);
        assertGrammar(mermaid);
        assertThat(mermaid.lines().count())
                .isEqualTo(7L + graph.path("nodes").size() + graph.path("edges").size());

        assertThat(report.path("scan").path("decisions").asInt()).isEqualTo(sessions * 20);
        assertThat(report.path("scan").path("braids").asInt()).isEqualTo(250);
        assertThat(report.path("ruleCounts").path("braid_missing_sessions").asInt())
                .isEqualTo(25);
        assertThat(report.path("ruleCounts").path("decision_missing_kind").asInt())
                .isEqualTo(sessions * 20);
        assertThat(report.path("findings")).hasSize(1000);
        assertThat(report.path("findingsTruncated").asBoolean()).isTrue();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    }

    // ------------------------------------------------------------------------------ read-only

    @Test
    void endpointsWriteNothing() throws Exception {
        Instant t0 = Instant.now().minus(20, ChronoUnit.DAYS);
        session("ro-1", "codex", "Read only", REPO, t0);
        decision("ro-d", "ro-1", REPO, "Decide", "no trailer", List.of(), t0.plusSeconds(1));
        handoff("ro-h", "ro-1", REPO, "Hand off", t0.plusSeconds(2));
        braid("ro-braid", null, "Orphan", t0.plusSeconds(3), "ro-gone");
        String before = fingerprint();

        body("/api/diagnostics/continuity");
        body("/api/export/braid-graph");
        body("/api/export/braid-graph?format=mermaid");

        assertThat(fingerprint()).isEqualTo(before);
    }

    // -------------------------------------------------------------------------------- fixtures

    private void session(String id, String source, String title, String cwd, Instant startedAt) {
        jdbc.update(
                "INSERT INTO agent_sessions (id, source, client_session_id, title, cwd, started_at, last_seen_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                id,
                source,
                "client-" + id,
                title,
                cwd,
                startedAt.toString(),
                startedAt.toString());
    }

    private void decision(
            String id,
            String session,
            String repo,
            String decision,
            String rationale,
            List<String> alternatives,
            Instant at)
            throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "decision");
        metadata.put("decision", decision);
        metadata.put("rationale", rationale);
        if (!alternatives.isEmpty()) {
            metadata.put("alternatives", alternatives);
        }
        metadata.put("repo", repo);
        event(id, session, "Decision", metadata, "Decision: " + decision + "\nWhy: " + rationale, at);
    }

    private void handoff(String id, String session, String repo, String summary, Instant at) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "handoff");
        metadata.put("contextSummary", summary);
        metadata.put("openLoops", List.of("loop"));
        metadata.put("repo", repo);
        event(id, session, "Handoff", metadata, "Handoff: " + summary, at);
    }

    private void observation(String id, String session, String repo, String text, Instant at) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "observation");
        if (repo != null) {
            metadata.put("repo", repo);
        }
        event(id, session, "Observation", metadata, text, at);
    }

    /** Raw rows, deliberately bypassing ingestion redaction, as legacy or imported history would be. */
    private void event(String id, String session, String type, Map<String, Object> metadata, String text, Instant at)
            throws Exception {
        jdbc.update(
                "INSERT INTO agent_events (id, session_id, source, client_session_id, event_type, role, text,"
                        + " metadata_json, observed_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id,
                session,
                "codex",
                "client-" + session,
                type,
                "assistant",
                text,
                json.writeValueAsString(metadata),
                at.toString());
    }

    private void braid(String id, String projectKey, String title, Instant createdAt, String... sessions) {
        jdbc.update("""
            INSERT INTO session_melds
                   (id, project_key, artifact_kind, title, body, provider, model, prompt_version,
                    execution_mode, saved_from_preview, metadata_json, created_at)
            VALUES (?, ?, 'braid', ?, ?, 'local', 'context-bundle', 'v1', 'export_bundle', 1, NULL, ?)
            """, id, projectKey, title, "body of " + title, createdAt.toString());
        for (String session : sessions) {
            jdbc.update(
                    "INSERT INTO session_meld_inputs (meld_id, session_id, input_order, included_summary) VALUES"
                            + " (?,?,?,0)",
                    id,
                    session,
                    sequence++);
        }
    }

    // --------------------------------------------------------------------------------- helpers

    private String body(String url) throws Exception {

        return http.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode getJson(String url) throws Exception {

        return json.readTree(body(url));
    }

    private String mermaid(String url) throws Exception {

        return http.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertGrammar(String mermaid) {
        assertThat(mermaid).endsWith("\n").doesNotContain("\r");
        mermaid.lines()
                .forEach(line -> assertThat(MERMAID_LINE.matcher(line).matches())
                        .as("generated Mermaid line: %s", line)
                        .isTrue());
    }

    private static List<String> subjects(JsonNode report, String rule) {
        List<String> subjects = new ArrayList<>();
        report.path("findings").forEach(f -> {
            if (rule.equals(f.path("rule").asText())) {
                subjects.add(f.path("subjectId").asText());
            }
        });

        return subjects;
    }

    private static JsonNode finding(JsonNode report, String rule) {
        for (JsonNode f : report.path("findings")) {
            if (rule.equals(f.path("rule").asText())) {

                return f;
            }
        }
        throw new AssertionError("no " + rule + " finding");
    }

    private static Map<String, String> nodeIdsByRef(JsonNode graph) {
        Map<String, String> ids = new LinkedHashMap<>();
        graph.path("nodes")
                .forEach(n -> ids.put(n.path("ref").asText(), n.path("id").asText()));

        return ids;
    }

    private static JsonNode node(JsonNode graph, String ref) {
        for (JsonNode n : graph.path("nodes")) {
            if (ref.equals(n.path("ref").asText())) {

                return n;
            }
        }
        throw new AssertionError("no node " + ref);
    }

    private static List<String> edges(JsonNode graph) {
        List<String> edges = new ArrayList<>();
        graph.path("edges")
                .forEach(e -> edges.add(e.path("from").asText() + " "
                        + e.path("relation").asText() + " " + e.path("to").asText()));

        return edges;
    }

    private static void assertEdgesReferenceNodes(JsonNode graph) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        graph.path("nodes").forEach(n -> ids.add(n.path("id").asText()));
        graph.path("edges").forEach(e -> {
            assertThat(ids).contains(e.path("from").asText());
            assertThat(ids).contains(e.path("to").asText());
        });
    }

    private static String decodeEntities(String mermaid) {
        Matcher matcher = Pattern.compile("#(\\d+);").matcher(mermaid);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    out, Matcher.quoteReplacement(Character.toString(Integer.parseInt(matcher.group(1)))));
        }
        matcher.appendTail(out);

        return out.toString();
    }

    /** Whether any Base64 run in the text still decodes to bytes containing the configured secret. */
    private static boolean decodableSecret(String text) {
        Map<String, Base64.Decoder> decoders = new LinkedHashMap<>();
        decoders.put("[A-Za-z0-9+/]{4,}={0,2}", Base64.getMimeDecoder());
        decoders.put("[A-Za-z0-9_-]{4,}", Base64.getUrlDecoder());
        for (Map.Entry<String, Base64.Decoder> decoder : decoders.entrySet()) {
            Matcher matcher = Pattern.compile(decoder.getKey()).matcher(text);
            while (matcher.find()) {
                String run = matcher.group();
                for (int offset = 0; offset < 4 && offset < run.length(); offset++) {
                    String slice = run.substring(offset).replace("=", "");
                    slice = slice.substring(0, slice.length() - slice.length() % 4);
                    try {
                        String decoded = new String(decoder.getValue().decode(slice), StandardCharsets.ISO_8859_1);
                        if (decoded.contains(CONFIGURED_SECRET)) {

                            return true;
                        }
                    } catch (IllegalArgumentException notBase64) {
                        // Not Base64 at this alignment.
                    }
                }
            }
        }

        return false;
    }

    private String fingerprint() {
        StringBuilder out = new StringBuilder();
        for (String table :
                List.of("agent_sessions", "agent_events", "session_melds", "session_meld_inputs", "project_aliases")) {
            out.append(table)
                    .append('=')
                    .append(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
                    .append(';');
        }
        jdbc.queryForList("SELECT * FROM agent_events ORDER BY id")
                .forEach(row -> out.append(row).append('\n'));
        jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id")
                .forEach(row -> out.append(row).append('\n'));
        jdbc.queryForList("SELECT * FROM session_melds ORDER BY id")
                .forEach(row -> out.append(row).append('\n'));

        return out.toString();
    }
}
