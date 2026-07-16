package dev.nathan.sbaagentic.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionLinkRepository {

    private final JdbcTemplate jdbcTemplate;

    public SessionLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SessionLink createLink(
            String parentSessionId,
            String childSessionId,
            LinkType linkType,
            String taskId) {
        requireText(parentSessionId, "Parent session id");
        requireText(childSessionId, "Child session id");

        SessionLink link = new SessionLink(
                UUID.randomUUID().toString(),
                parentSessionId,
                childSessionId,
                linkType,
                taskId,
                Instant.now());
        try {
            jdbcTemplate.update("""
                    INSERT INTO session_links (
                        id, parent_session_id, child_session_id, link_type, task_id, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    link.id(),
                    link.parentSessionId(),
                    link.childSessionId(),
                    link.linkType().value(),
                    link.taskId(),
                    link.createdAt().toString());
        }
        catch (DataAccessException ex) {
            if (isSqliteConstraintViolation(ex)) {
                throw new DataIntegrityViolationException(
                        "Session link already exists for " + parentSessionId + " -> " + childSessionId
                                + " (" + linkType.value() + ")",
                        ex);
            }
            throw ex;
        }
        return link;
    }

    public List<SessionLink> linksWhereParent(String sessionId) {
        requireText(sessionId, "Session id");
        return jdbcTemplate.query("""
                SELECT id, parent_session_id, child_session_id, link_type, task_id, created_at
                  FROM session_links
                 WHERE parent_session_id = ?
                 ORDER BY %s ASC
                """.formatted(sortableInstant("created_at")), this::mapLink, sessionId);
    }

    public List<SessionLink> linksWhereChild(String sessionId) {
        requireText(sessionId, "Session id");
        return jdbcTemplate.query("""
                SELECT id, parent_session_id, child_session_id, link_type, task_id, created_at
                  FROM session_links
                 WHERE child_session_id = ?
                 ORDER BY %s ASC
                """.formatted(sortableInstant("created_at")), this::mapLink, sessionId);
    }

    public List<SessionLink> linksForTaskId(String taskId) {
        requireText(taskId, "Task id");
        return jdbcTemplate.query("""
                SELECT id, parent_session_id, child_session_id, link_type, task_id, created_at
                  FROM session_links
                 WHERE task_id = ?
                 ORDER BY %s ASC
                """.formatted(sortableInstant("created_at")), this::mapLink, taskId);
    }

    private SessionLink mapLink(ResultSet rs, int rowNum) throws SQLException {
        return new SessionLink(
                rs.getString("id"),
                rs.getString("parent_session_id"),
                rs.getString("child_session_id"),
                LinkType.fromValue(rs.getString("link_type")),
                rs.getString("task_id"),
                Instant.parse(rs.getString("created_at")));
    }

    /**
     * {@link Instant#toString()} emits zero, three, six, or nine fractional digits. SQLite compares
     * TEXT lexically, so a later six-digit value can otherwise sort before an earlier three-digit
     * value. Right-padding the stored fraction for comparison preserves existing timestamp strings
     * while making their ordering chronological.
     */
    private static String sortableInstant(String column) {
        return """
                CASE
                    WHEN instr(%1$s, '.') = 0
                        THEN substr(%1$s, 1, length(%1$s) - 1) || '.000000000Z'
                    ELSE substr(%1$s, 1, length(%1$s) - 1)
                         || substr('000000000', 1,
                                   9 - (length(%1$s) - instr(%1$s, '.') - 1))
                         || 'Z'
                END
                """.formatted(column);
    }

    /**
     * SQLite has no entry in Spring's vendor error-code tables and reports a null SQL state, so
     * {@link JdbcTemplate} can only translate a constraint violation as the generic {@link
     * org.springframework.jdbc.UncategorizedSQLException} — never the more specific {@link
     * DataIntegrityViolationException} callers actually want to catch. This walks the cause chain
     * for the underlying {@link SQLException} and checks SQLite's {@code SQLITE_CONSTRAINT} error
     * code (19) directly.
     */
    private static boolean isSqliteConstraintViolation(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && sqlException.getErrorCode() == 19) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
