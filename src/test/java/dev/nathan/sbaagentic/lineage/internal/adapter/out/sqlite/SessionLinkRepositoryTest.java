package dev.nathan.sbaagentic.lineage.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nathan.sbaagentic.lineage.LinkType;
import dev.nathan.sbaagentic.lineage.SessionLink;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-session-link-repository-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
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
        SessionLink created = repository.createLink("parent-session", "child-session", LinkType.SPAWNED);

        assertThat(created.id()).isNotBlank();
        assertThat(created.parentSessionId()).isEqualTo("parent-session");
        assertThat(created.childSessionId()).isEqualTo("child-session");
        assertThat(created.linkType()).isEqualTo(LinkType.SPAWNED);
        assertThat(created.createdAt()).isNotNull();
        assertThat(repository.linksWhereParent("parent-session")).containsExactly(created);
        assertThat(repository.linksWhereChild("child-session")).containsExactly(created);
    }

    @Test
    void duplicateTripleFailsButDifferentLinkTypeForSamePairSucceeds() {
        repository.createLink("parent-session", "child-session", LinkType.SPAWNED);

        assertThatThrownBy(() -> repository.createLink("parent-session", "child-session", LinkType.SPAWNED))
                .isInstanceOf(DataIntegrityViolationException.class);

        SessionLink steered = repository.createLink("parent-session", "child-session", LinkType.STEERED);
        assertThat(repository.linksWhereParent("parent-session"))
                .extracting(SessionLink::linkType)
                .containsExactly(LinkType.SPAWNED, LinkType.STEERED);
        assertThat(steered.linkType()).isEqualTo(LinkType.STEERED);
    }

    @Test
    void childCountsGroupsLinksByRequestedParents() {
        repository.createLink("parent-a", "child-1", LinkType.SPAWNED);
        repository.createLink("parent-a", "child-2", LinkType.CONTINUED);
        repository.createLink("parent-b", "child-3", LinkType.SPAWNED);
        repository.createLink("parent-c", "child-4", LinkType.SPAWNED);

        assertThat(repository.childCounts(List.of("parent-a", "parent-b", "parent-missing")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("parent-a", 2L, "parent-b", 1L));
    }

    @Test
    void childCountsWithoutIdsReturnsEmptyMap() {
        repository.createLink("parent-a", "child-1", LinkType.SPAWNED);

        assertThat(repository.childCounts(List.of())).isEmpty();
    }

    @Test
    void blankSessionIdsFailBeforeMutation() {
        assertThatThrownBy(() -> repository.createLink(" ", "child-session", LinkType.SPAWNED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.createLink("parent-session", "\n\t", LinkType.SPAWNED))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM session_links", Integer.class))
                .isZero();
    }
}
