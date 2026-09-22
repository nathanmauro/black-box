package dev.nathan.sbaagentic.judgment.internal.adapter.out.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.nathan.sbaagentic.judgment.EventJudgment;
import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgmentRepository;
import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatEvent;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JudgmentSqlRepository implements JudgmentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JudgmentSqlRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void saveForBeat(Beat beat, Judgment judgment) {
        // Persist resolved session identities and rule-derived scores, not only provider kin_0 keys.
        ObjectNode answers = objectMapper.createObjectNode();
        answers.put("phase", judgment.phase());
        answers.put("salience", judgment.salience());
        answers.put("novelty", judgment.novelty());
        answers.put("human", judgment.human());
        answers.set("kin", objectMapper.valueToTree(judgment.kin()));
        answers.set("raw", judgment.answers());
        String answersJson = toJson(answers);
        for (BeatEvent event : beat.events()) {
            jdbcTemplate.update("""
                    INSERT INTO event_judgments (
                        event_id, session_id, beat_id, judge, model, version, answers_json, judged_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO UPDATE SET
                        session_id = excluded.session_id,
                        beat_id = excluded.beat_id,
                        judge = excluded.judge,
                        model = excluded.model,
                        version = excluded.version,
                        answers_json = excluded.answers_json,
                        judged_at = excluded.judged_at
                    """,
                    event.id(),
                    beat.sessionId(),
                    beat.id(),
                    judgment.judge(),
                    judgment.model(),
                    judgment.version(),
                    answersJson,
                    judgment.judgedAt().toString());
        }
    }

    @Override
    public Optional<EventJudgment> findByEventId(String eventId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT event_id, session_id, beat_id, judge, model, version, answers_json, judged_at
                      FROM event_judgments
                     WHERE event_id = ?
                    """, this::mapJudgment, eventId));
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<EventJudgment> findForSession(String sessionId, int limit) {
        return jdbcTemplate.query("""
                SELECT event_id, session_id, beat_id, judge, model, version, answers_json, judged_at
                  FROM event_judgments
                 WHERE session_id = ?
                 ORDER BY judged_at DESC, event_id DESC
                 LIMIT ?
                """, this::mapJudgment, sessionId, limit);
    }

    private EventJudgment mapJudgment(ResultSet rs, int rowNum) throws SQLException {
        return new EventJudgment(
                rs.getString("event_id"),
                rs.getString("session_id"),
                rs.getString("beat_id"),
                rs.getString("judge"),
                rs.getString("model"),
                rs.getString("version"),
                fromJson(rs.getString("answers_json")),
                Instant.parse(rs.getString("judged_at")));
    }

    private String toJson(JsonNode answers) {
        try {
            return objectMapper.writeValueAsString(answers);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Invalid judgment answers", ex);
        }
    }

    private JsonNode fromJson(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Invalid persisted judgment answers", ex);
        }
    }
}
