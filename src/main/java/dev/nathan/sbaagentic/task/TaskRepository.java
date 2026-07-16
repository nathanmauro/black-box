package dev.nathan.sbaagentic.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaskRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String TASK_COLUMNS = """
            t.id, t.spec_id, t.project_key, t.title, t.lane, t.status, t.priority,
            t.created_by, t.claimed_by, t.blocked_reason, t.result_handoff_id,
            t.created_at, t.updated_at
            """;

    private static final String SNAPSHOT_COLUMNS = TASK_COLUMNS + """
            , s.id AS snapshot_spec_id, s.project_key AS snapshot_spec_project_key,
            s.title AS snapshot_spec_title, s.body AS snapshot_spec_body,
            s.spec_ref AS snapshot_spec_ref, s.status AS snapshot_spec_status,
            s.created_by AS snapshot_spec_created_by, s.created_at AS snapshot_spec_created_at,
            s.updated_at AS snapshot_spec_updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public TaskSpec createSpec(
            String projectKey,
            String title,
            String body,
            Map<String, Object> specRef,
            String createdBy) {
        requireText(projectKey, "Project key");
        requireText(title, "Spec title");
        requireText(body, "Spec body");
        requireText(createdBy, "Spec creator");

        Instant now = Instant.now();
        TaskSpec spec = new TaskSpec(
                UUID.randomUUID().toString(),
                projectKey,
                title,
                body,
                specRef,
                SpecStatus.ACTIVE,
                createdBy,
                now,
                now);
        jdbcTemplate.update("""
                INSERT INTO specs (
                    id, project_key, title, body, spec_ref, status, created_by, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                spec.id(),
                spec.projectKey(),
                spec.title(),
                spec.body(),
                toJson(spec.specRef()),
                spec.status().value(),
                spec.createdBy(),
                spec.createdAt().toString(),
                spec.updatedAt().toString());
        return spec;
    }

    public Optional<TaskSpec> findSpec(String specId) {
        requireText(specId, "Spec id");
        return jdbcTemplate.query("""
                SELECT id, project_key, title, body, spec_ref, status, created_by, created_at, updated_at
                  FROM specs
                 WHERE id = ?
                """, this::mapSpec, specId).stream().findFirst();
    }

    @Transactional
    public TaskChange enqueueTask(String specId, String title, String lane, int priority, String createdBy) {
        requireText(specId, "Spec id");
        requireText(title, "Task title");
        requireText(lane, "Task lane");
        requireText(createdBy, "Task creator");

        TaskSpec spec = findSpec(specId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown spec id: " + specId));
        Instant now = Instant.now();
        Task task = new Task(
                UUID.randomUUID().toString(),
                spec.id(),
                spec.projectKey(),
                title,
                lane,
                TaskStatus.OPEN,
                priority,
                createdBy,
                null,
                null,
                null,
                now,
                now);
        jdbcTemplate.update("""
                INSERT INTO tasks (
                    id, spec_id, project_key, title, lane, status, priority, created_by,
                    claimed_by, blocked_reason, result_handoff_id, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(),
                task.specId(),
                task.projectKey(),
                task.title(),
                task.lane(),
                task.status().value(),
                task.priority(),
                task.createdBy(),
                task.claimedBy(),
                task.blockedReason(),
                task.resultHandoffId(),
                task.createdAt().toString(),
                task.updatedAt().toString());
        TaskEvent event = appendEvent(
                task.id(), TaskEventType.CREATED, createdBy, null, TaskStatus.OPEN, null, now);
        return new TaskChange(new TaskSnapshot(task, spec), event);
    }

    public Optional<TaskSnapshot> findTask(String taskId) {
        requireText(taskId, "Task id");
        return jdbcTemplate.query("""
                SELECT %s
                  FROM tasks t
                  JOIN specs s ON s.id = t.spec_id
                 WHERE t.id = ?
                """.formatted(SNAPSHOT_COLUMNS), this::mapSnapshot, taskId).stream().findFirst();
    }

    public List<TaskSnapshot> listTasks(TaskQuery query) {
        TaskQuery normalized = query == null ? TaskQuery.all() : query;
        if (normalized.projectKey() != null) {
            requireText(normalized.projectKey(), "Project key");
        }
        if (normalized.lane() != null) {
            requireText(normalized.lane(), "Task lane");
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SNAPSHOT_COLUMNS)
                .append(" FROM tasks t JOIN specs s ON s.id = t.spec_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (normalized.projectKey() != null) {
            sql.append(" AND t.project_key = ?");
            args.add(normalized.projectKey());
        }
        if (normalized.lane() != null) {
            sql.append(" AND t.lane = ?");
            args.add(normalized.lane());
        }
        if (normalized.status() != null) {
            sql.append(" AND t.status = ?");
            args.add(normalized.status().value());
        }
        sql.append(" ORDER BY t.priority DESC, ")
                .append(sortableInstant("t.created_at"))
                .append(" ASC");
        return jdbcTemplate.query(sql.toString(), this::mapSnapshot, args.toArray());
    }

    /**
     * Claims with one SQLite statement so candidate selection and ownership cannot race. The
     * xerial driver exposes {@code RETURNING} through a result set, hence {@link JdbcTemplate#query}
     * rather than {@code update}.
     */
    @Transactional
    public Optional<TaskChange> claimNextTask(String lane, String agent) {
        requireText(lane, "Task lane");
        requireText(agent, "Claiming agent");

        Instant now = Instant.now();
        List<Task> claimed = jdbcTemplate.query("""
                UPDATE tasks
                   SET status = 'in_progress',
                       claimed_by = ?,
                       updated_at = ?
                 WHERE id = (
                       SELECT id
                         FROM tasks
                        WHERE status = 'open'
                          AND lane = ?
                        ORDER BY priority DESC, %s ASC
                        LIMIT 1
                 )
                RETURNING id, spec_id, project_key, title, lane, status, priority,
                          created_by, claimed_by, blocked_reason, result_handoff_id,
                          created_at, updated_at
                """.formatted(sortableInstant("created_at")), this::mapTask, agent, now.toString(), lane);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        Task task = claimed.getFirst();
        TaskEvent event = appendEvent(
                task.id(),
                TaskEventType.CLAIMED,
                agent,
                TaskStatus.OPEN,
                TaskStatus.IN_PROGRESS,
                null,
                now);
        TaskSpec spec = findSpec(task.specId()).orElseThrow();
        return Optional.of(new TaskChange(new TaskSnapshot(task, spec), event));
    }

    @Transactional
    public Optional<TaskChange> updateTask(TaskUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Task update is required");
        }
        requireText(update.taskId(), "Task id");
        requireText(update.actor(), "Task actor");
        if (update.expectedStatus() == null) {
            throw new IllegalArgumentException("Expected task status is required");
        }
        if (update.targetStatus() == null) {
            throw new IllegalArgumentException("Target task status is required");
        }
        if (update.eventType() == null) {
            throw new IllegalArgumentException("Task event type is required");
        }

        Instant now = Instant.now();
        List<Task> updated = jdbcTemplate.query("""
                UPDATE tasks
                   SET status = ?,
                       claimed_by = ?,
                       blocked_reason = ?,
                       result_handoff_id = ?,
                       updated_at = ?
                 WHERE id = ?
                   AND status = ?
                RETURNING id, spec_id, project_key, title, lane, status, priority,
                          created_by, claimed_by, blocked_reason, result_handoff_id,
                          created_at, updated_at
                """,
                this::mapTask,
                update.targetStatus().value(),
                update.claimedBy(),
                update.blockedReason(),
                update.resultHandoffId(),
                now.toString(),
                update.taskId(),
                update.expectedStatus().value());
        if (updated.isEmpty()) {
            return Optional.empty();
        }

        Task task = updated.getFirst();
        TaskEvent event = appendEvent(
                task.id(),
                update.eventType(),
                update.actor(),
                update.expectedStatus(),
                update.targetStatus(),
                update.detail(),
                now);
        TaskSpec spec = findSpec(task.specId()).orElseThrow();
        return Optional.of(new TaskChange(new TaskSnapshot(task, spec), event));
    }

    public List<TaskEvent> eventsForTask(String taskId) {
        requireText(taskId, "Task id");
        return jdbcTemplate.query("""
                SELECT id, task_id, type, actor, from_status, to_status, detail_json, observed_at
                  FROM task_events
                 WHERE task_id = ?
                 ORDER BY observed_at ASC, id ASC
                """, this::mapEvent, taskId);
    }

    public List<Task> listTasksBySpec(String specId) {
        requireText(specId, "Spec id");
        return jdbcTemplate.query("""
                SELECT id, spec_id, project_key, title, lane, status, priority,
                       created_by, claimed_by, blocked_reason, result_handoff_id,
                       created_at, updated_at
                  FROM tasks
                 WHERE spec_id = ?
                 ORDER BY %s ASC
                """.formatted(sortableInstant("created_at")), this::mapTask, specId);
    }

    public List<TaskEvent> eventsByType(TaskEventType type) {
        if (type == null) {
            throw new IllegalArgumentException("Task event type is required");
        }
        return jdbcTemplate.query("""
                SELECT id, task_id, type, actor, from_status, to_status, detail_json, observed_at
                  FROM task_events
                 WHERE type = ?
                 ORDER BY %s ASC, id ASC
                """.formatted(sortableInstant("observed_at")), this::mapEvent, type.value());
    }

    public TaskAnnotation appendAnnotation(
            String taskId,
            AnnotationKind kind,
            String actor,
            String text,
            Map<String, Object> dataJson) {
        requireText(taskId, "Task id");
        requireText(actor, "Task actor");
        requireText(text, "Annotation text");

        Map<String, Object> detail;
        if (dataJson == null) {
            detail = Map.of("kind", kind.value(), "text", text);
        }
        else {
            detail = new LinkedHashMap<>();
            detail.put("kind", kind.value());
            detail.put("text", text);
            detail.put("dataJson", dataJson);
        }
        TaskEvent event = appendEvent(
                taskId, TaskEventType.NOTE, actor, null, null, detail, Instant.now());
        Map<String, Object> persistedDetail = event.detail();
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedDataJson =
                (Map<String, Object>) persistedDetail.get("dataJson");
        return new TaskAnnotation(
                event.id(),
                event.taskId(),
                AnnotationKind.fromValue((String) persistedDetail.get("kind")),
                event.actor(),
                (String) persistedDetail.get("text"),
                persistedDataJson,
                event.observedAt());
    }

    private TaskEvent appendEvent(
            String taskId,
            TaskEventType type,
            String actor,
            TaskStatus fromStatus,
            TaskStatus toStatus,
            Map<String, Object> detail,
            Instant observedAt) {
        requireText(actor, "Task actor");
        TaskEvent event = new TaskEvent(
                UUID.randomUUID().toString(),
                taskId,
                type,
                actor,
                fromStatus,
                toStatus,
                detail,
                observedAt);
        jdbcTemplate.update("""
                INSERT INTO task_events (
                    id, task_id, type, actor, from_status, to_status, detail_json, observed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                event.taskId(),
                event.type().value(),
                event.actor(),
                event.fromStatus() == null ? null : event.fromStatus().value(),
                event.toStatus() == null ? null : event.toStatus().value(),
                toJson(event.detail()),
                event.observedAt().toString());
        return event;
    }

    private TaskSpec mapSpec(ResultSet rs, int rowNum) throws SQLException {
        return new TaskSpec(
                rs.getString("id"),
                rs.getString("project_key"),
                rs.getString("title"),
                rs.getString("body"),
                fromJson(rs.getString("spec_ref")),
                SpecStatus.fromValue(rs.getString("status")),
                rs.getString("created_by"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private Task mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new Task(
                rs.getString("id"),
                rs.getString("spec_id"),
                rs.getString("project_key"),
                rs.getString("title"),
                rs.getString("lane"),
                TaskStatus.fromValue(rs.getString("status")),
                rs.getInt("priority"),
                rs.getString("created_by"),
                rs.getString("claimed_by"),
                rs.getString("blocked_reason"),
                rs.getString("result_handoff_id"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private TaskSnapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        Task task = mapTask(rs, rowNum);
        TaskSpec spec = new TaskSpec(
                rs.getString("snapshot_spec_id"),
                rs.getString("snapshot_spec_project_key"),
                rs.getString("snapshot_spec_title"),
                rs.getString("snapshot_spec_body"),
                fromJson(rs.getString("snapshot_spec_ref")),
                SpecStatus.fromValue(rs.getString("snapshot_spec_status")),
                rs.getString("snapshot_spec_created_by"),
                Instant.parse(rs.getString("snapshot_spec_created_at")),
                Instant.parse(rs.getString("snapshot_spec_updated_at")));
        return new TaskSnapshot(task, spec);
    }

    private TaskEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        String fromStatus = rs.getString("from_status");
        String toStatus = rs.getString("to_status");
        return new TaskEvent(
                rs.getString("id"),
                rs.getString("task_id"),
                TaskEventType.fromValue(rs.getString("type")),
                rs.getString("actor"),
                fromStatus == null ? null : TaskStatus.fromValue(fromStatus),
                toStatus == null ? null : TaskStatus.fromValue(toStatus),
                fromJson(rs.getString("detail_json")),
                Instant.parse(rs.getString("observed_at")));
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize task metadata", ex);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to parse stored task metadata", ex);
        }
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

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
