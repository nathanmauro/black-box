package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Ingest-time human-turn classification, human titles, and the {@code humanOnly} read paths. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-human-turn-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
class HumanTurnStoreTest {

    private static final String REMINDER =
            "<system-reminder>\nSessionStart hook additional context\n</system-reminder>";
    private static final String NOTIFICATION =
            "<task-notification><task-id>abc</task-id><status>completed</status></task-notification>";

    @Autowired
    EventRecorder ingestService;

    @Autowired
    RecordingSqlStore repository;

    @Autowired
    JdbcTemplate jdbc;

    private String source;
    private int tick;

    private String start() {
        source = "ht" + UUID.randomUUID().toString().substring(0, 8);
        tick = 0;

        return "client-" + source;
    }

    private String ingest(String client, String eventType, String text) {

        return ingest(client, eventType, text, Map.of());
    }

    private String ingest(String client, String eventType, String text, Map<String, Object> metadata) {
        tick++;

        return ingestService
                .ingest(new EventIngestRequest(
                        source,
                        client,
                        null,
                        eventType,
                        "user",
                        text,
                        "/tmp/" + source,
                        null,
                        null,
                        null,
                        metadata,
                        Instant.parse("2026-09-01T10:00:00Z").plusSeconds(tick)))
                .eventId();
    }

    private AgentSession session(String client) {

        return repository.findSession(source, client).orElseThrow();
    }

    @Test
    void humanPromptStoresHumanTextAndMachineTurnsStoreNull() {
        String client = start();
        String human = ingest(client, "UserPromptSubmit", "please rename the widget");
        String note = ingest(client, "UserPromptSubmit", NOTIFICATION);
        String tool = ingest(client, "PostToolUse", "ls output");

        assertThat(repository.findEventById(human).orElseThrow().humanText()).isEqualTo("please rename the widget");
        assertThat(repository.findEventById(note).orElseThrow().humanText()).isNull();
        assertThat(repository.findEventById(tool).orElseThrow().humanText()).isNull();
    }

    @Test
    void storedTextStaysByteIdenticalWhenBoilerplateIsStrippedFromHumanText() {
        String client = start();
        String raw = REMINDER + "\nfix the flaky test";
        String id = ingest(client, "UserPromptSubmit", raw);

        AgentEvent event = repository.findEventById(id).orElseThrow();
        assertThat(event.text()).isEqualTo(raw);
        assertThat(event.humanText()).isEqualTo("fix the flaky test");
    }

    @Test
    void firstHumanTurnIsSetByTheFirstHumanTurnEvenAfterAMachinePrompt() {
        String client = start();
        ingest(client, "UserPromptSubmit", NOTIFICATION);
        assertThat(session(client).firstHumanTurn()).isNull();
        ingest(client, "UserPromptSubmit", "the real question");
        ingest(client, "UserPromptSubmit", "a later follow up");

        assertThat(session(client).firstHumanTurn()).isEqualTo("the real question");
    }

    @Test
    void humanTitleReplacesJunkTextTitleAndExplicitTitle() {
        String junkClient = start();
        ingest(junkClient, "SessionStart", REMINDER + "\nboot");
        // Boilerplate never becomes a title; the remaining text is used at TEXT rank.
        assertThat(session(junkClient).title()).isEqualTo("boot");
        ingest(junkClient, "UserPromptSubmit", "Investigate the slow feed\nsecond line");
        assertThat(session(junkClient).title()).isEqualTo("Investigate the slow feed");

        String explicitClient = start();
        ingest(explicitClient, "SessionStart", "hello", Map.of("title", "Client title"));
        assertThat(session(explicitClient).title()).isEqualTo("Client title");
        ingest(explicitClient, "UserPromptSubmit", "What did I say earlier?");
        assertThat(session(explicitClient).title()).isEqualTo("What did I say earlier?");
    }

    @Test
    void reminderOnlyFirstEventFallsThroughToTheFallbackTitle() {
        String client = start();
        ingest(client, "SessionStart", REMINDER);

        assertThat(session(client).title()).isEqualTo(source + " SessionStart");
    }

    @Test
    void aiTitleStillOutranksHumanTitle() {
        String client = start();
        ingest(client, "UserPromptSubmit", "human words here");
        repository.saveSummaryAndTitle(session(client).id(), "summary", "AI title", TitleRank.AI);
        ingest(client, "UserPromptSubmit", "more human words");

        assertThat(session(client).title()).isEqualTo("AI title");
        assertThat(session(client).firstHumanTurn()).isEqualTo("human words here");
    }

    @Test
    void humanOnlyFeedReturnsOnlyHumanTurnsAcrossKeysetPagesAndFacetsAgree() {
        String client = start();
        List<String> humanIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            humanIds.add(ingest(client, "UserPromptSubmit", "question " + i));
            ingest(client, "PostToolUse", "tool " + i);
            ingest(client, "UserPromptSubmit", NOTIFICATION);
        }
        String query = "source:" + source;

        List<String> paged = new ArrayList<>();
        String before = null;
        do {
            EventFeedResponse page = repository.feed(query, false, before, null, List.of(), 2, true);
            assertThat(page.items())
                    .allSatisfy(item -> assertThat(item.humanText()).isNotNull());
            page.items().forEach(item -> paged.add(item.id()));
            before = page.nextBefore();
        } while (before != null);

        assertThat(paged).containsExactlyInAnyOrderElementsOf(humanIds).hasSize(5);
        assertThat(repository
                        .feed(query, false, null, null, List.of(), 100, false)
                        .count())
                .isEqualTo(15);
        assertThat(repository.facetCounts(query, false, List.of(), true).total())
                .isEqualTo(5);
        assertThat(repository.facetCounts(query, false, List.of(), false).total())
                .isEqualTo(15);
    }

    @Test
    void perSessionTranscriptEventsAndSessionListRespectHumanOnly() {
        String humanClient = start();
        String humanSource = source;
        ingest(humanClient, "UserPromptSubmit", "the one human line");
        ingest(humanClient, "PostToolUse", "tool call");
        String machineClient = "machine-" + humanSource;
        ingest(machineClient, "UserPromptSubmit", NOTIFICATION);

        AgentSession humanSession = session(humanClient);
        assertThat(repository
                        .feedForSession(humanSession.id(), null, null, 50, true)
                        .items())
                .extracting(EventFeedItem::humanText)
                .containsExactly("the one human line");
        assertThat(repository
                        .feedForSession(humanSession.id(), null, null, 50, false)
                        .count())
                .isEqualTo(2);
        assertThat(repository.eventsForSession(humanSession.id(), 50, true))
                .extracting(AgentEvent::humanText)
                .containsExactly("the one human line");

        List<String> humanSessions = repository.recentSessions(500, true, true).stream()
                .map(AgentSession::clientSessionId)
                .toList();
        assertThat(humanSessions).contains(humanClient).doesNotContain(machineClient);
        assertThat(repository.recentSessions(500, true, false).stream().map(AgentSession::clientSessionId))
                .contains(humanClient, machineClient);
    }

    @Test
    void humanOnlyLocalSearchMatchesOnlyHumanTurns() {
        String client = start();
        String marker = "zebrafish" + UUID.randomUUID().toString().substring(0, 6);
        String human = ingest(client, "UserPromptSubmit", "remember the " + marker);
        ingest(client, "PostToolUse", "tool saw " + marker);

        var memory = new dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite.MemorySqlQueryAdapter(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC());

        assertThat(memory.searchEvents(marker, List.of(), 20, false)).hasSize(2);
        assertThat(memory.searchEvents(marker, List.of(), 20, true))
                .extracting(AgentEvent::id)
                .containsExactly(human);
        assertThat(memory.searchEvents("source:" + source, List.of(), 20, true))
                .extracting(AgentEvent::id)
                .containsExactly(human);
    }
}
