package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.SessionHeader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SessionHeadersTest {
    @TempDir
    Path directory;

    private JdbcTemplate jdbc;
    private RecordingSqlStore store;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("headers.db")));
        // Deliberately omit all heavy columns: this projection cannot accidentally read them.
        jdbc.execute("CREATE TABLE agent_sessions (id TEXT PRIMARY KEY, title TEXT, source TEXT)");
        store = new RecordingSqlStore(jdbc, new ObjectMapper(), Clock.systemUTC(), null);
    }

    @Test
    void readsOnlyExactRequestedHeadersAndPreservesNullableTitles() {
        String quotedId = "peer') OR 1=1 --";
        jdbc.update("INSERT INTO agent_sessions VALUES (?, ?, ?)", quotedId, "Quoted", "claude");
        jdbc.update("INSERT INTO agent_sessions VALUES ('untitled', NULL, 'codex')");
        jdbc.update("INSERT INTO agent_sessions VALUES ('unrequested', 'Private', 'claude')");

        var headers = store.sessionHeaders(Arrays.asList(quotedId, "untitled", "missing", quotedId, null));

        assertThat(headers).hasSize(2);
        assertThat(headers.get(quotedId)).isEqualTo(new SessionHeader(quotedId, "Quoted", "claude"));
        assertThat(headers.get("untitled")).isEqualTo(new SessionHeader("untitled", null, "codex"));
        assertThat(headers).doesNotContainKeys("missing", "unrequested");
    }

    @Test
    void readsMoreThanOneBatchWithoutDroppingOrDuplicatingIds() {
        List<String> ids = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 1001; index++) {
            String id = "session-" + index;
            ids.add(id);
            rows.add(new Object[] {id, "Title " + index, "claude"});
        }
        jdbc.batchUpdate("INSERT INTO agent_sessions VALUES (?, ?, ?)", rows);
        ids.add("session-0");

        var headers = store.sessionHeaders(ids);

        assertThat(headers).hasSize(1001);
        for (int index = 0; index < 1001; index++) {
            assertThat(headers.get("session-" + index))
                    .isEqualTo(new SessionHeader("session-" + index, "Title " + index, "claude"));
        }
    }

    @Test
    void emptyInputDoesNotRequireASessionTable() {
        jdbc.execute("DROP TABLE agent_sessions");

        assertThat(store.sessionHeaders(null)).isEmpty();
        assertThat(store.sessionHeaders(List.of())).isEmpty();
        assertThat(store.sessionHeaders(Arrays.asList(null, null))).isEmpty();
    }
}
