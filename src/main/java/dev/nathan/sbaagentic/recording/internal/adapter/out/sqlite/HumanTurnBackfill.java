package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.recording.HumanTurns;
import dev.nathan.sbaagentic.recording.TitleRank;
import dev.nathan.sbaagentic.recording.Titles;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-derives {@code agent_events.human_text}, {@code agent_sessions.first_human_turn}, and human
 * session titles whenever the stored classifier version is below {@link HumanTurns#VERSION}.
 *
 * <p>Runs once at startup (after {@link RecordingSqlStore#ensureSchema()} has added the columns),
 * works on SQLite and PostgreSQL through plain JDBC, and is idempotent: a second run at the same
 * version does nothing. Only prompt-shaped events are read; the stored {@code text} is never
 * modified.
 */
@Component
public class HumanTurnBackfill {

    private static final Logger log = LoggerFactory.getLogger(HumanTurnBackfill.class);

    private static final int BATCH = 1_000;

    /** Result of one {@link #run()}; {@code ran == false} means the stored version was current. */
    public record Result(boolean ran, int eventsChanged, int sessionsWithFirstTurn, int titlesApplied) {}

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;
    private final Clock clock;

    // RecordingSqlStore is injected only to order this after its column migrations.
    public HumanTurnBackfill(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Clock clock,
            RecordingSqlStore ordering) {
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @PostConstruct
    public void backfillOnStart() {
        try {
            run();
        } catch (RuntimeException ex) {
            // Never block startup: the columns simply stay as they are until the next start.
            log.warn("Human-turn reclassification failed; will retry on next start", ex);
        }
    }

    public Result run() {
        Integer stored = storedVersion();
        if (stored != null && stored >= HumanTurns.VERSION) {

            return new Result(false, 0, 0, 0);
        }
        Result result = transaction.execute(status -> reclassify());
        log.info(
                "Human-turn reclassification v{}: {} events changed, {} sessions with a first human turn, {} titles applied",
                HumanTurns.VERSION,
                result.eventsChanged(),
                result.sessionsWithFirstTurn(),
                result.titlesApplied());

        return result;
    }

    private Integer storedVersion() {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT version FROM human_turn_state WHERE id = 1", (rs, rowNum) -> rs.getInt("version"));

        return rows.isEmpty() ? null : rows.get(0);
    }

    private Result reclassify() {
        int eventsChanged = reclassifyEvents();
        int sessions = recomputeFirstHumanTurns();
        int titles = applyHumanTitles();
        String now = clock.instant().toString();
        int updated = jdbcTemplate.update(
                "UPDATE human_turn_state SET version = ?, updated_at = ? WHERE id = 1", HumanTurns.VERSION, now);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO human_turn_state (id, version, updated_at) VALUES (1, ?, ?)", HumanTurns.VERSION, now);
        }

        return new Result(true, eventsChanged, sessions, titles);
    }

    private int reclassifyEvents() {
        List<String> types = new ArrayList<>(HumanTurns.PROMPT_EVENT_TYPES);
        Collections.sort(types);
        String placeholders = String.join(", ", Collections.nCopies(types.size(), "?"));
        List<Object[]> changes = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT id, event_type, text, human_text FROM agent_events"
                        + " WHERE lower(replace(replace(event_type, '_', ''), '-', '')) IN ("
                        + placeholders + ")",
                rs -> {
                    String derived = HumanTurns.extract(rs.getString("event_type"), rs.getString("text"))
                            .orElse(null);
                    String current = rs.getString("human_text");
                    if (derived == null ? current != null : !derived.equals(current)) {
                        changes.add(new Object[] {derived, rs.getString("id")});
                    }
                },
                types.toArray());
        for (int from = 0; from < changes.size(); from += BATCH) {
            jdbcTemplate.batchUpdate(
                    "UPDATE agent_events SET human_text = ? WHERE id = ?",
                    changes.subList(from, Math.min(changes.size(), from + BATCH)));
        }

        return changes.size();
    }

    private int recomputeFirstHumanTurns() {
        record Turn(Instant at, String id, String text) {}
        Map<String, Turn> earliest = new HashMap<>();
        jdbcTemplate.query(
                "SELECT session_id, id, observed_at, human_text FROM agent_events WHERE human_text IS NOT NULL", rs -> {
                    Turn turn = new Turn(
                            Instant.parse(rs.getString("observed_at")), rs.getString("id"), rs.getString("human_text"));
                    earliest.merge(rs.getString("session_id"), turn, (a, b) -> {
                        int byTime = a.at().compareTo(b.at());

                        return (byTime != 0 ? byTime : a.id().compareTo(b.id())) <= 0 ? a : b;
                    });
                });
        jdbcTemplate.update("UPDATE agent_sessions SET first_human_turn = NULL WHERE first_human_turn IS NOT NULL");
        List<Object[]> updates = new ArrayList<>(earliest.size());
        earliest.forEach((sessionId, turn) -> updates.add(new Object[] {turn.text(), sessionId}));
        for (int from = 0; from < updates.size(); from += BATCH) {
            jdbcTemplate.batchUpdate(
                    "UPDATE agent_sessions SET first_human_turn = ? WHERE id = ?",
                    updates.subList(from, Math.min(updates.size(), from + BATCH)));
        }

        return updates.size();
    }

    /** Sessions below HUMAN (never AI or LEGACY) adopt their first human turn as the title. */
    private int applyHumanTitles() {
        List<Object[]> updates = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT id, title, first_human_turn FROM agent_sessions"
                        + " WHERE first_human_turn IS NOT NULL AND title_rank <= ?",
                rs -> {
                    String title = Titles.sanitize(Titles.firstLine(rs.getString("first_human_turn")));
                    if (!title.equals(rs.getString("title"))) {
                        updates.add(new Object[] {title, TitleRank.HUMAN, rs.getString("id")});
                    }
                },
                TitleRank.HUMAN);
        // Rows already at HUMAN with an unchanged title are skipped above; lower-ranked rows with a
        // coincidentally identical title still need their rank raised.
        jdbcTemplate.update(
                "UPDATE agent_sessions SET title_rank = ? WHERE first_human_turn IS NOT NULL AND title_rank < ?",
                TitleRank.HUMAN,
                TitleRank.HUMAN);
        for (int from = 0; from < updates.size(); from += BATCH) {
            jdbcTemplate.batchUpdate(
                    "UPDATE agent_sessions SET title = ?, title_rank = ? WHERE id = ?",
                    updates.subList(from, Math.min(updates.size(), from + BATCH)));
        }

        return updates.size();
    }
}
