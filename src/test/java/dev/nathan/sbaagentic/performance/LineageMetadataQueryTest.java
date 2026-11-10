package dev.nathan.sbaagentic.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.lineage.internal.adapter.out.sqlite.SessionLinkRepository;
import dev.nathan.sbaagentic.lineage.internal.application.SessionDagService;
import dev.nathan.sbaagentic.lineage.internal.application.SessionLinkService;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Counts real prepared reads through the services and SQLite, including 32 KiB session summaries. */
class LineageMetadataQueryTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 500})
    void lineageViewsUseBoundedMetadataQueries(int peers) {
        var source =
                new CountingDataSource(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("lineage.db")));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String summary = "summary payload ".repeat(2048);
        List<Object[]> sessions = new ArrayList<>();
        List<Object[]> edges = new ArrayList<>();
        sessions.add(new Object[] {"center", "Center", summary});
        for (int peer = 0; peer < peers; peer++) {
            String id = "peer-" + peer;
            sessions.add(new Object[] {id, "Title " + peer, summary});
            edges.add(new Object[] {
                "edge-" + peer,
                id,
                Instant.parse("2026-11-10T12:00:00Z").plusSeconds(peer).toString()
            });
        }
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.batchUpdate(
                    """
                    INSERT INTO agent_sessions
                        (id, source, client_session_id, title, summary, started_at, last_seen_at, event_count)
                    VALUES (?, 'claude', ?, ?, ?, '2026-11-10T12:00:00Z', '2026-11-10T12:00:00Z', 0)
                    """,
                    sessions.stream()
                            .map(row -> new Object[] {row[0], row[0], row[1], row[2]})
                            .toList());
            jdbc.batchUpdate("""
                INSERT INTO session_links (id, parent_session_id, child_session_id, link_type, created_at)
                VALUES (?, 'center', ?, 'spawned', ?)
                """, edges);
        });
        var catalog = new RecordingSqlStore(jdbc, new ObjectMapper(), Clock.systemUTC(), null);
        var links = new SessionLinkService(new SessionLinkRepository(jdbc), catalog);
        var dag = new SessionDagService(links, catalog);

        source.queries.clear();
        var linkResponse = links.linksForSession("center");
        List<String> linkQueries = List.copyOf(source.queries);
        source.queries.clear();
        var dagResponse = dag.forSession("center");
        List<String> dagQueries = List.copyOf(source.queries);
        System.out.printf(
                "lineage-query-count peers=%d links=%d dag=%d%n", peers, linkQueries.size(), dagQueries.size());

        assertThat(linkResponse.children()).hasSize(peers);
        assertThat(dagResponse.nodes()).hasSize(peers + 1);
        assertThat(dagResponse.edges()).hasSize(peers);
        assertThat(linkQueries).hasSize(2 + (peers + 499) / 500);
        assertThat(dagQueries).hasSize(2 + (peers + 1 + 499) / 500);
        assertThat(linkQueries).noneMatch(sql -> sql.contains("summary"));
        assertThat(dagQueries).noneMatch(sql -> sql.contains("summary"));
        assertThat(linkQueries)
                .allMatch(
                        sql -> sql.chars().filter(character -> character == '?').count() <= 500);
        assertThat(dagQueries)
                .allMatch(
                        sql -> sql.chars().filter(character -> character == '?').count() <= 500);
    }

    private static final class CountingDataSource extends DelegatingDataSource {
        private final List<String> queries = new ArrayList<>();

        CountingDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();

            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")
                                && arguments[0] instanceof String sql
                                && sql.stripLeading().startsWith("SELECT")) {
                            queries.add(sql);
                        }
                        try {

                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }
    }
}
