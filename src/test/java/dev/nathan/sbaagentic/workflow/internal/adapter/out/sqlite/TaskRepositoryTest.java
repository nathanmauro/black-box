package dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import dev.nathan.sbaagentic.workflow.SpecStatus;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.TaskQuery;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;
import dev.nathan.sbaagentic.workflow.internal.domain.TaskUpdate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/task-repository-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class TaskRepositoryTest {

    @Autowired
    TaskRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void resetQueue() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_task_event_insert");
        jdbcTemplate.update("DELETE FROM task_events");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM specs");
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_task_event_insert");
    }

    @Test
    void enqueueAndFilteredListPreserveSpecAndTaskFields() {
        Map<String, Object> specRef = Map.of(
                "repo", "black-box",
                "path", "docs/spec.md",
                "sha", "abc123");
        TaskSpec createdSpec = repository.createSpec(
                "/repos/black-box",
                "Shared task queue",
                "Canonical spec body\nwith exact spacing.",
                specRef,
                "codex");

        TaskChange created = repository.enqueueTask(
                createdSpec.id(), "Implement claim", "codex", 17, "claude");

        assertThat(repository.findSpec(createdSpec.id())).contains(createdSpec);
        assertThat(repository.listTasks(new TaskQuery("/repos/black-box", "codex", TaskStatus.OPEN)))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.task().id()).isEqualTo(created.snapshot().task().id());
                    assertThat(snapshot.task().projectKey()).isEqualTo("/repos/black-box");
                    assertThat(snapshot.task().title()).isEqualTo("Implement claim");
                    assertThat(snapshot.task().lane()).isEqualTo("codex");
                    assertThat(snapshot.task().priority()).isEqualTo(17);
                    assertThat(snapshot.task().createdBy()).isEqualTo("claude");
                    assertThat(snapshot.task().createdAt()).isEqualTo(created.snapshot().task().createdAt());
                    assertThat(snapshot.task().updatedAt()).isEqualTo(created.snapshot().task().updatedAt());
                    assertThat(snapshot.spec().body()).isEqualTo("Canonical spec body\nwith exact spacing.");
                    assertThat(snapshot.spec().specRef()).isEqualTo(specRef);
                    assertThat(snapshot.spec().createdBy()).isEqualTo("codex");
                    assertThat(snapshot.spec().createdAt()).isEqualTo(createdSpec.createdAt());
                    assertThat(snapshot.spec().updatedAt()).isEqualTo(createdSpec.updatedAt());
                });
        assertThat(created.event().type()).isEqualTo(TaskEventType.CREATED);
        assertThat(created.event().fromStatus()).isNull();
        assertThat(created.event().toStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(repository.listTasks(new TaskQuery(null, "claude", null))).isEmpty();
    }

    @Test
    void blankRequiredFieldsAndUnknownEnumsFailBeforeMutation() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.createSpec(" ", "title", "body", null, "codex"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.createSpec("project", " ", "body", null, "codex"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.createSpec("project", "title", "\n\t", null, "codex"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.createSpec("project", "title", "body", null, " "));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM specs", Integer.class)).isZero();

        TaskSpec spec = repository.createSpec("project", "title", "body", null, "codex");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.enqueueTask(spec.id(), " ", "codex", 0, "codex"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.enqueueTask(spec.id(), "task", " ", 0, "codex"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.enqueueTask(spec.id(), "task", "codex", 0, " "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.claimNextTask(" ", "agent"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.claimNextTask("codex", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> SpecStatus.fromValue("paused"));
        assertThatIllegalArgumentException().isThrownBy(() -> TaskStatus.fromValue("running"));
        assertThatIllegalArgumentException().isThrownBy(() -> TaskEventType.fromValue("task.running"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_events", Integer.class)).isZero();
    }

    @Test
    void claimIsLaneScopedThenPriorityAndFifoOrdered() {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        Task old = repository.enqueueTask(spec.id(), "old", "codex", 5, "manual").snapshot().task();
        Task newer = repository.enqueueTask(spec.id(), "newer", "codex", 5, "manual").snapshot().task();
        Task highest = repository.enqueueTask(spec.id(), "highest", "codex", 9, "manual").snapshot().task();
        Task otherLane = repository.enqueueTask(spec.id(), "other lane", "claude", 100, "manual").snapshot().task();
        jdbcTemplate.update("UPDATE tasks SET created_at = '2026-07-09T00:00:00Z' WHERE id = ?", old.id());
        jdbcTemplate.update("UPDATE tasks SET created_at = '2026-07-09T00:01:00Z' WHERE id = ?", newer.id());
        jdbcTemplate.update("UPDATE tasks SET created_at = '2026-07-09T00:02:00Z' WHERE id = ?", highest.id());

        assertThat(repository.claimNextTask("codex", "agent-a"))
                .get()
                .extracting(change -> change.snapshot().task().id())
                .isEqualTo(highest.id());
        assertThat(repository.claimNextTask("codex", "agent-a"))
                .get()
                .extracting(change -> change.snapshot().task().id())
                .isEqualTo(old.id());
        assertThat(repository.claimNextTask("codex", "agent-a"))
                .get()
                .extracting(change -> change.snapshot().task().id())
                .isEqualTo(newer.id());
        assertThat(repository.claimNextTask("codex", "agent-a")).isEmpty();
        assertThat(repository.findTask(otherLane.id()).orElseThrow().task().status()).isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void fifoOrderingNormalizesMixedInstantPrecision() {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        Task earlier = repository.enqueueTask(spec.id(), "earlier", "codex", 5, "manual").snapshot().task();
        Task later = repository.enqueueTask(spec.id(), "later", "codex", 5, "manual").snapshot().task();
        jdbcTemplate.update(
                "UPDATE tasks SET created_at = '2026-07-09T00:00:00.123Z' WHERE id = ?", earlier.id());
        jdbcTemplate.update(
                "UPDATE tasks SET created_at = '2026-07-09T00:00:00.123001Z' WHERE id = ?", later.id());

        assertThat(repository.listTasks(new TaskQuery(null, "codex", TaskStatus.OPEN)))
                .extracting(snapshot -> snapshot.task().id())
                .containsExactly(earlier.id(), later.id());
        assertThat(repository.claimNextTask("codex", "agent-a"))
                .get()
                .extracting(change -> change.snapshot().task().id())
                .isEqualTo(earlier.id());
        assertThat(repository.claimNextTask("codex", "agent-a"))
                .get()
                .extracting(change -> change.snapshot().task().id())
                .isEqualTo(later.id());
    }

    @Test
    void listTasksExcludesStatusesInSql() {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        Task open = repository.enqueueTask(spec.id(), "open", "codex", 5, "manual").snapshot().task();
        Task done = repository.enqueueTask(spec.id(), "done", "codex", 5, "manual").snapshot().task();
        Task cancelled = repository.enqueueTask(spec.id(), "cancelled", "codex", 5, "manual")
                .snapshot().task();
        jdbcTemplate.update("UPDATE tasks SET status = 'done' WHERE id = ?", done.id());
        jdbcTemplate.update("UPDATE tasks SET status = 'cancelled' WHERE id = ?", cancelled.id());

        assertThat(repository.listTasks(new TaskQuery(
                        null,
                        "codex",
                        null,
                        List.of(TaskStatus.DONE, TaskStatus.CANCELLED),
                        10,
                        0)))
                .extracting(snapshot -> snapshot.task().id())
                .containsExactly(open.id());
    }

    @Test
    void listTasksPaginatesStableTiesWithoutDuplicatesOrGaps() {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        List<Task> tasks = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            tasks.add(repository.enqueueTask(spec.id(), "task-" + index, "codex", 5, "manual")
                    .snapshot().task());
        }
        jdbcTemplate.update("UPDATE tasks SET created_at = '2026-07-09T00:00:00Z'");

        List<String> expected = tasks.stream()
                .map(Task::id)
                .sorted()
                .toList();
        List<String> actual = new ArrayList<>();
        for (int offset = 0; offset < 7; offset += 3) {
            repository.listTasks(new TaskQuery(null, "codex", null, List.of(), 3, offset)).stream()
                    .map(snapshot -> snapshot.task().id())
                    .forEach(actual::add);
        }

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(actual).doesNotHaveDuplicates();
    }

    @Test
    void pooledConnectionsEnforceForeignKeysAndRetainBusyTimeout() throws Exception {
        List<Connection> connections = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                connections.add(dataSource.getConnection());
            }
            assertThat(connections).allSatisfy(connection -> {
                assertThat(pragma(connection, "foreign_keys")).isEqualTo(1);
                assertThat(pragma(connection, "busy_timeout")).isEqualTo(5000);
            });
        }
        finally {
            for (Connection connection : connections.reversed()) {
                connection.close();
            }
        }

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO tasks (
                    id, spec_id, project_key, title, lane, status, priority,
                    created_by, created_at, updated_at
                )
                VALUES (
                    'orphan-task', 'missing-spec', 'project', 'task', 'codex', 'open', 0,
                    'manual', '2026-07-09T00:00:00Z', '2026-07-09T00:00:00Z'
                )
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("FOREIGN KEY constraint failed");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO task_events (id, task_id, type, actor, observed_at)
                VALUES ('orphan-event', 'missing-task', 'task.created', 'manual', '2026-07-09T00:00:00Z')
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("FOREIGN KEY constraint failed");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_events", Integer.class)).isZero();
    }

    @Test
    void simultaneousClaimsHaveExactlyOneWinnerAndOneClaimEvent() throws Exception {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        Task eligible = repository.enqueueTask(spec.id(), "only task", "codex", 1, "manual").snapshot().task();
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<TaskChange>> first = executor.submit(() -> {
                barrier.await();
                return repository.claimNextTask("codex", "agent-a");
            });
            Future<Optional<TaskChange>> second = executor.submit(() -> {
                barrier.await();
                return repository.claimNextTask("codex", "agent-b");
            });

            List<Optional<TaskChange>> results = List.of(
                    first.get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                    second.get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count())
                    .as("exactly one concurrent claimant wins")
                    .isEqualTo(1);
            assertThat(results.stream().filter(Optional::isEmpty).count())
                    .as("exactly one concurrent claimant receives an empty result")
                    .isEqualTo(1);
        }

        Task finalTask = repository.findTask(eligible.id()).orElseThrow().task();
        assertThat(finalTask.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(finalTask.claimedBy()).isIn("agent-a", "agent-b");
        assertThat(repository.eventsForTask(eligible.id()))
                .filteredOn(event -> event.type() == TaskEventType.CLAIMED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.fromStatus()).isEqualTo(TaskStatus.OPEN);
                    assertThat(event.toStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
                    assertThat(event.actor()).isEqualTo(finalTask.claimedBy());
                });
    }

    @Test
    void updateRequiresTheExpectedSourceStatus() {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        Task task = repository.enqueueTask(spec.id(), "task", "codex", 1, "manual").snapshot().task();
        Task claimed = repository.claimNextTask("codex", "agent-a").orElseThrow().snapshot().task();
        int eventsBefore = repository.eventsForTask(task.id()).size();

        Optional<TaskChange> staleUpdate = repository.updateTask(new TaskUpdate(
                task.id(),
                TaskStatus.OPEN,
                TaskStatus.BLOCKED,
                "agent-a",
                TaskEventType.BLOCKED,
                claimed.claimedBy(),
                "waiting",
                null,
                Map.of("reason", "waiting")));

        assertThat(staleUpdate).isEmpty();
        assertThat(repository.findTask(task.id()).orElseThrow().task().status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(repository.eventsForTask(task.id())).hasSize(eventsBefore);
    }

    @Test
    void updateWithExpectedStatusMutatesTaskAndAppendsMatchingEvent() {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        Task task = repository.enqueueTask(spec.id(), "task", "codex", 1, "manual").snapshot().task();
        Task claimed = repository.claimNextTask("codex", "agent-a").orElseThrow().snapshot().task();

        TaskChange blocked = repository.updateTask(new TaskUpdate(
                task.id(),
                TaskStatus.IN_PROGRESS,
                TaskStatus.BLOCKED,
                "agent-a",
                TaskEventType.BLOCKED,
                claimed.claimedBy(),
                "waiting for input",
                null,
                Map.of("reason", "waiting for input"))).orElseThrow();

        assertThat(blocked.snapshot().task().status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(blocked.snapshot().task().claimedBy()).isEqualTo("agent-a");
        assertThat(blocked.snapshot().task().blockedReason()).isEqualTo("waiting for input");
        assertThat(blocked.event().type()).isEqualTo(TaskEventType.BLOCKED);
        assertThat(blocked.event().fromStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(blocked.event().toStatus()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(blocked.event().detail()).containsEntry("reason", "waiting for input");
        assertThat(repository.eventsForTask(task.id()))
                .extracting(TaskEvent::type)
                .containsExactly(TaskEventType.CREATED, TaskEventType.CLAIMED, TaskEventType.BLOCKED);
    }

    @Test
    void taskMutationAndEventAppendRollBackTogether() {
        TaskSpec spec = repository.createSpec("project", "queue", "body", null, "manual");
        Task task = repository.enqueueTask(spec.id(), "task", "codex", 1, "manual").snapshot().task();
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_task_event_insert
                BEFORE INSERT ON task_events
                BEGIN
                    SELECT RAISE(ABORT, 'forced task event failure');
                END
                """);

        assertThatThrownBy(() -> repository.claimNextTask("codex", "agent-a"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("forced task event failure");

        Task afterFailure = repository.findTask(task.id()).orElseThrow().task();
        assertThat(afterFailure.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(afterFailure.claimedBy()).isNull();
        assertThat(repository.eventsForTask(task.id()))
                .extracting(TaskEvent::type)
                .containsExactly(TaskEventType.CREATED);
    }

    private static int pragma(Connection connection, String name) {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            if (!result.next()) {
                throw new AssertionError("PRAGMA " + name + " returned no row");
            }
            return result.getInt(1);
        }
        catch (SQLException ex) {
            throw new AssertionError("Unable to read PRAGMA " + name, ex);
        }
    }
}
