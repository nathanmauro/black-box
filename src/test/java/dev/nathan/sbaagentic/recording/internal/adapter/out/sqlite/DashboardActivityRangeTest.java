package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.DashboardStats;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DashboardActivityRangeTest {
    private static final String BASELINE = """
        SELECT date(observed_at) AS day, COUNT(*) AS count
          FROM agent_events
         WHERE date(observed_at) >= date('now', ?)
           AND date(observed_at) <= date('now')
         GROUP BY date(observed_at)
         ORDER BY day ASC
        """;

    @TempDir
    Path directory;

    private CapturingJdbcTemplate jdbc;
    private RecordingSqlStore store;

    @BeforeEach
    void setUp() {
        jdbc = new CapturingJdbcTemplate(directory.resolve("dashboard.db"));
        jdbc.execute("CREATE TABLE agent_sessions (id TEXT PRIMARY KEY, source TEXT)");
        jdbc.execute("CREATE TABLE agent_events (id TEXT PRIMARY KEY, source TEXT, event_type TEXT, observed_at TEXT)");
        jdbc.execute("CREATE INDEX idx_agent_events_observed ON agent_events (observed_at DESC, id DESC)");
        store = new RecordingSqlStore(jdbc, new ObjectMapper(), Clock.systemUTC(), null);
    }

    @Test
    void preservesDateBucketsAtBothEdgesIncludingFractionalSecondsAndOffsets() {
        LocalDate today = LocalDate.parse(jdbc.queryForObject("SELECT date('now')", String.class));
        List<Object[]> rows = new ArrayList<>();
        for (int offset = -16; offset <= 3; offset++) {
            String day = today.plusDays(offset).toString();
            for (String time : List.of(
                    "T00:00:00Z",
                    "T23:59:59.999999999Z",
                    "T00:00:00+14:00",
                    "T23:59:59-14:00",
                    " 12:00:00",
                    "T24:00:00Z")) {
                rows.add(new Object[] {Integer.toString(rows.size()), day + time});
            }
        }
        jdbc.batchUpdate("INSERT INTO agent_events VALUES (?, 'claude', 'Stop', ?)", rows);

        var actual = store.dashboardStats();

        assertThat(actual.totalEvents()).isEqualTo(rows.size());
        assertThat(actual.recentActivity()).isEqualTo(dailyCounts(BASELINE));
        assertThat(actual.recentActivity()).hasSize(14);
    }

    @Test
    void usesABoundedSearchOfTheExistingTimestampIndex() {
        assertThat(store.dashboardStats().recentActivity()).isEmpty();
        List<String> plan = jdbc.query(
                "EXPLAIN QUERY PLAN " + jdbc.activitySql, (row, number) -> row.getString("detail"), "-13 days");

        assertThat(plan)
                .anySatisfy(detail -> assertThat(detail)
                        .contains("SEARCH agent_events USING COVERING INDEX idx_agent_events_observed")
                        .contains("observed_at>? AND observed_at<?"));
    }

    @Test
    @EnabledIfSystemProperty(named = "sba.performance.benchmark", matches = "true")
    void reportsDailyQueryTimingsWithoutATimingAssertion() {
        LocalDate today = LocalDate.parse(jdbc.queryForObject("SELECT date('now')", String.class));
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 300_000; index++) {
            rows.add(new Object[] {
                Integer.toString(index), today.minusDays(index / 100).toString() + "T12:00:00Z"
            });
        }
        var connection = jdbc.getDataSource();
        new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(connection))
                .executeWithoutResult(
                        status -> jdbc.batchUpdate("INSERT INTO agent_events VALUES (?, 'claude', 'Stop', ?)", rows));
        var actual = store.dashboardStats().recentActivity();
        String optimized = jdbc.activitySql;
        assertThat(actual).isEqualTo(dailyCounts(BASELINE));
        long[] before = new long[11];
        long[] after = new long[11];
        for (int sample = -2; sample < 11; sample++) {
            long start = System.nanoTime();
            dailyCounts(BASELINE);
            long middle = System.nanoTime();
            dailyCounts(optimized);
            long end = System.nanoTime();
            if (sample >= 0) {
                before[sample] = middle - start;
                after[sample] = end - middle;
            }
        }
        Arrays.sort(before);
        Arrays.sort(after);
        System.out.printf(
                "dashboard daily query rows=300000 recent=1400 baseline=%.3fms optimized=%.3fms%n",
                before[5] / 1_000_000.0, after[5] / 1_000_000.0);
    }

    private List<DashboardStats.DailyCount> dailyCounts(String sql) {

        return jdbc.query(
                sql,
                (row, number) -> new DashboardStats.DailyCount(row.getString("day"), row.getLong("count")),
                "-13 days");
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String activitySql;

        CapturingJdbcTemplate(Path database) {
            super(new DriverManagerDataSource("jdbc:sqlite:" + database));
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.stripLeading().startsWith("SELECT date(observed_at)")) {
                activitySql = sql;
            }

            return super.query(sql, rowMapper, args);
        }
    }
}
