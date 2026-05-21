package dev.nathan.sbaagentic.event;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:event-ingest-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.elasticsearch.enabled=false"
})
class EventIngestServiceTest {

    @Autowired
    EventIngestService ingestService;

    @Autowired
    EventRepository repository;

    @Test
    void ingestCreatesSessionAndSearchableEvent() {
        IngestResponse response = ingestService.ingest(new EventIngestRequest(
                "Claude",
                "session-1",
                "turn-1",
                "UserPromptSubmit",
                "user",
                "Wire the local agent hook into the Spring Boot app.",
                "/tmp/project",
                null,
                null,
                null,
                Map.of("title", "Hook wiring"),
                Instant.parse("2026-05-21T12:00:00Z")));

        assertThat(response.source()).isEqualTo("claude");
        assertThat(repository.recentSessions(10)).hasSize(1);
        assertThat(repository.recentSessions(10).getFirst().eventCount()).isEqualTo(1);
        assertThat(repository.searchEvents("Spring Boot", 10))
                .extracting(AgentEvent::id)
                .contains(response.eventId());
    }
}
