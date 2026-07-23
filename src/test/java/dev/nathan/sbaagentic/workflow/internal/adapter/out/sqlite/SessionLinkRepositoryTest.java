package dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.workflow.LinkType;
import dev.nathan.sbaagentic.workflow.SessionLink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/session-link-repository-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class SessionLinkRepositoryTest {

    @Autowired
    SessionLinkRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetLinks() {
        jdbcTemplate.update("DELETE FROM session_links");
    }

    @Test
    void createLinkPersistsAndRoundTripsThroughBothDirections() {
        SessionLink created = repository.createLink(
                "parent-session", "child-session", LinkType.SPAWNED, "task-1");

        assertThat(created.id()).isNotBlank();
        assertThat(created.parentSessionId()).isEqualTo("parent-session");
        assertThat(created.childSessionId()).isEqualTo("child-session");
        assertThat(created.linkType()).isEqualTo(LinkType.SPAWNED);
        assertThat(created.taskId()).isEqualTo("task-1");
        assertThat(created.createdAt()).isNotNull();
        assertThat(repository.linksWhereParent("parent-session")).containsExactly(created);
        assertThat(repository.linksWhereChild("child-session")).containsExactly(created);
    }

    @Test
    void duplicateTripleFailsButDifferentLinkTypeForSamePairSucceeds() {
        repository.createLink("parent-session", "child-session", LinkType.SPAWNED, null);

        assertThatThrownBy(() -> repository.createLink(
                "parent-session", "child-session", LinkType.SPAWNED, "other-task"))
                .isInstanceOf(DataIntegrityViolationException.class);

        SessionLink steered = repository.createLink(
                "parent-session", "child-session", LinkType.STEERED, null);
        assertThat(repository.linksWhereParent("parent-session"))
                .extracting(SessionLink::linkType)
                .containsExactly(LinkType.SPAWNED, LinkType.STEERED);
        assertThat(steered.linkType()).isEqualTo(LinkType.STEERED);
    }

    @Test
    void linksForTaskIdReturnsOnlyMatchingLinks() {
        SessionLink first = repository.createLink(
                "parent-a", "child-a", LinkType.SPAWNED, "task-1");
        SessionLink second = repository.createLink(
                "parent-b", "child-b", LinkType.CONTINUED, "task-1");
        repository.createLink("parent-c", "child-c", LinkType.STEERED, "task-2");
        repository.createLink("parent-d", "child-d", LinkType.SPAWNED, null);

        assertThat(repository.linksForTaskId("task-1")).containsExactly(first, second);
    }

    @Test
    void childCountsGroupsLinksByRequestedParents() {
        repository.createLink("parent-a", "child-1", LinkType.SPAWNED, null);
        repository.createLink("parent-a", "child-2", LinkType.CONTINUED, null);
        repository.createLink("parent-b", "child-3", LinkType.SPAWNED, "task-1");
        repository.createLink("parent-c", "child-4", LinkType.SPAWNED, null);

        assertThat(repository.childCounts(List.of("parent-a", "parent-b", "parent-missing")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("parent-a", 2L, "parent-b", 1L));
    }

    @Test
    void childCountsWithoutIdsReturnsEmptyMap() {
        repository.createLink("parent-a", "child-1", LinkType.SPAWNED, null);

        assertThat(repository.childCounts(List.of())).isEmpty();
    }

    @Test
    void blankSessionIdsFailBeforeMutation() {
        assertThatThrownBy(() -> repository.createLink(
                " ", "child-session", LinkType.SPAWNED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.createLink(
                "parent-session", "\n\t", LinkType.SPAWNED, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_links", Integer.class)).isZero();
    }
}
