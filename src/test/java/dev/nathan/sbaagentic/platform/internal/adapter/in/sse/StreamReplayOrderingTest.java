package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite.StreamReplayRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class StreamReplayOrderingTest {
    @Test
    void wholeSecondsAndFractionalSecondsSortChronologicallyAndResumeExclusively() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + System.getProperty("java.io.tmpdir") + "/bb-replay-order-"
                + UUID.randomUUID() + ".db");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO agent_sessions (id,source,client_session_id,title,started_at,last_seen_at) "
                + "VALUES ('s','codex','s','fixture','2026-09-21T12:00:00Z','2026-09-21T12:00:01Z')");
        String[] stamps = {
            "2026-09-21T12:00:00Z", "2026-09-21T12:00:00.000000001Z", "2026-09-21T12:00:00.100Z", "2026-09-21T12:00:01Z"
        };
        for (int i = 0; i < stamps.length; i++) {
            jdbc.update(
                    "INSERT INTO agent_events (id,session_id,source,client_session_id,event_type,observed_at) "
                            + "VALUES (?,'s','codex','s','Decision',?)",
                    "e" + i,
                    stamps[i]);
        }
        var repository = new StreamReplayRepository(jdbc);
        assertThat(repository.eventsSince(Instant.parse(stamps[0])))
                .extracting(e -> e.id())
                .containsExactly("e0", "e1", "e2", "e3");
        assertThat(repository.eventsAfterCursor(stamps[0] + "|e0"))
                .extracting(e -> e.id())
                .containsExactly("e1", "e2", "e3");
    }
}
