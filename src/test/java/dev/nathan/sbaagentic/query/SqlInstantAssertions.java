package dev.nathan.sbaagentic.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** The same native SQL/Java comparison contract runs on both supported database engines. */
public final class SqlInstantAssertions {
    private SqlInstantAssertions() {}

    public static void assertDialect(JdbcTemplate jdbc, boolean postgres) {
        List<String> values = List.of(
                Instant.MIN.toString(),
                "-10000-01-01T00:00:00Z",
                "-0001-12-31T23:59:59.999999999Z",
                "0000-01-01T00:00:00Z",
                "0001-01-01T00:00:00Z",
                "9999-12-31T23:59:59.999999999Z",
                "+10000-01-01T00:00:00Z",
                "+100000-01-01T00:00:00.000000001Z",
                Instant.MAX.toString(),
                "2026-10-03T01:00:00Z",
                "2026-10-03T01:00:00.0Z",
                "2026-10-03T01:00:00.000000001Z",
                "2026-10-03T01:00:00.001Z",
                "2026-10-03T01:00:00.100Z",
                "2026-10-03T01:00:00.100000001Z",
                "2026-10-03T01:00:00.999999999Z",
                "2026-10-03T01:00:01Z");
        SqlInstant column = SqlInstant.column("e.observed_at", postgres);
        for (String value : values) {
            assertThat(jdbc.queryForObject(
                            "SELECT " + column.expression() + " FROM (SELECT CAST(? AS TEXT) AS observed_at) e",
                            String.class,
                            value))
                    .as("native key for %s", value)
                    .isEqualTo(SqlInstant.key(Instant.parse(value)));
        }
        String union = values.stream()
                .map(ignored -> "SELECT CAST(? AS TEXT) AS observed_at")
                .collect(Collectors.joining(" UNION ALL "));
        assertThat(jdbc
                        .queryForList(
                                "SELECT observed_at FROM (" + union + ") e ORDER BY " + column.expression() + " DESC",
                                String.class,
                                values.toArray())
                        .stream()
                        .map(Instant::parse))
                .containsExactlyElementsOf(values.stream()
                        .map(Instant::parse)
                        .sorted(Comparator.reverseOrder())
                        .toList());
        var bound = new ArrayList<Object>();
        SqlInstant.bind(bound, Instant.MAX);
        assertThat(bound).containsExactly(SqlInstant.key(Instant.MAX));
    }
}
