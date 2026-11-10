package dev.nathan.sbaagentic.lineage.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.lineage.DagEdge;
import dev.nathan.sbaagentic.lineage.LinkType;
import dev.nathan.sbaagentic.lineage.SessionLineageOperations;
import dev.nathan.sbaagentic.lineage.SessionLink;
import dev.nathan.sbaagentic.lineage.SessionRef;
import dev.nathan.sbaagentic.lineage.internal.application.port.SessionLinkStore;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionHeader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionLineageMetadataTest {
    private final RecordingCatalog catalog = mock(RecordingCatalog.class);
    private final List<SessionLink> parents = List.of(
            link("p1", "parent", "center", LinkType.SPAWNED),
            link("p2", "parent", "center", LinkType.CONTINUED),
            link("p3", "missing", "center", LinkType.STEERED),
            link("self", "center", "center", LinkType.SPAWNED));
    private final List<SessionLink> children = List.of(
            link("c1", "center", "untitled", LinkType.SPAWNED),
            link("c2", "center", "parent", LinkType.STEERED),
            link("c3", "center", "empty-title", LinkType.CONTINUED),
            link("self", "center", "center", LinkType.SPAWNED));
    private final Map<String, SessionHeader> headers = Map.of(
            "center", new SessionHeader("center", null, "claude"),
            "parent", new SessionHeader("parent", "Parent", "codex"),
            "untitled", new SessionHeader("untitled", null, "claude"),
            "empty-title", new SessionHeader("empty-title", "", "codex"));

    @Test
    void linksKeepDirectionOrderTypesAndMissingPeerMetadata() {
        SessionLinkStore store = mock(SessionLinkStore.class);
        when(store.linksWhereChild("center")).thenReturn(parents);
        when(store.linksWhereParent("center")).thenReturn(children);
        when(catalog.sessionHeaders(anyList())).thenReturn(headers);

        var response = new SessionLinkService(store, catalog).linksForSession("center");

        assertThat(response.parents()).extracting(view -> view.linkId()).containsExactly("p1", "p2", "p3", "self");
        assertThat(response.children()).extracting(view -> view.linkId()).containsExactly("c1", "c2", "c3", "self");
        assertThat(response.parents())
                .extracting(view -> view.linkType())
                .containsExactly(LinkType.SPAWNED, LinkType.CONTINUED, LinkType.STEERED, LinkType.SPAWNED);
        assertThat(response.parents().get(2).session()).isEqualTo(new SessionRef("missing", null, null));
        assertThat(response.children().get(0).session()).isEqualTo(new SessionRef("untitled", null, "claude"));
        assertThat(response.children().get(2).session()).isEqualTo(new SessionRef("empty-title", "", "codex"));
        verify(catalog).sessionHeaders(List.of("parent", "missing", "center", "untitled", "empty-title"));
        verify(catalog, never()).findSessionById(anyString());
    }

    @Test
    void dagKeepsCenterThenChildrenThenParentsAndHydratesEachPeerOnce() {
        SessionLineageOperations links = mock(SessionLineageOperations.class);
        when(links.linksWhereParent("center")).thenReturn(children);
        when(links.linksWhereChild("center")).thenReturn(parents);
        when(catalog.sessionHeaders(anyList())).thenReturn(headers);

        var response = new SessionDagService(links, catalog).forSession("center");

        assertThat(response.nodes())
                .extracting(node -> node.ref())
                .containsExactly("center", "untitled", "parent", "empty-title", "missing");
        assertThat(response.nodes())
                .extracting(node -> node.label())
                .containsExactly("center", "untitled", "Parent", "", "missing");
        assertThat(response.edges())
                .containsExactly(
                        new DagEdge("session:center", "session:untitled", "spawned"),
                        new DagEdge("session:center", "session:parent", "steered"),
                        new DagEdge("session:center", "session:empty-title", "continued"),
                        new DagEdge("session:center", "session:center", "spawned"),
                        new DagEdge("session:parent", "session:center", "spawned"),
                        new DagEdge("session:parent", "session:center", "continued"),
                        new DagEdge("session:missing", "session:center", "steered"));
        verify(catalog).sessionHeaders(List.of("center", "untitled", "parent", "empty-title", "missing"));
        verify(catalog, never()).findSessionById(anyString());
    }

    private static SessionLink link(String id, String parent, String child, LinkType type) {

        return new SessionLink(id, parent, child, type, Instant.parse("2026-11-10T12:00:00Z"));
    }
}
