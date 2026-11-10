package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.lineage.LinkType;
import dev.nathan.sbaagentic.lineage.SessionLineageOperations;
import dev.nathan.sbaagentic.lineage.SessionLink;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventRecorded;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StreamPayloadWorkTest {
    private final EventBroadcaster broadcaster = new EventBroadcaster();
    private final RecordingCatalog recording = mock(RecordingCatalog.class);
    private final SessionLineageOperations lineage = mock(SessionLineageOperations.class);
    private final StreamPayloadFactory factory = spy(new StreamPayloadFactory(recording, lineage));
    private final EventStreamListener listener = new EventStreamListener(broadcaster, factory);
    private final Instant now = Instant.parse("2026-11-10T12:00:00Z");
    private final AgentSession session =
            new AgentSession("session", "claude", "client", "Fixture", "/fixture", null, now, now, 1, null);
    private final AgentEvent event = new AgentEvent(
            "event", "session", "claude", "client", null, "Stop", "assistant", "Done", null, null, null, Map.of(), now);

    @AfterEach
    void close() {
        broadcaster.closeAll();
    }

    @Test
    void idleCaptureDoesNoStreamPayloadOrLineageWork() {
        for (int capture = 0; capture < 1_000; capture++) {
            listener.broadcastRecordedEvent(new EventRecorded(session, event));
        }

        verifyNoInteractions(factory, recording, lineage);
    }

    @Test
    void durableOnlySubscribersDrainWithoutBuildingUnusedEventPayloads() {
        AtomicInteger drains = new AtomicInteger();
        broadcaster.register(() -> true, "cursor", false, cursor -> {
            drains.incrementAndGet();

            return new EventBroadcaster.Page(List.of(), false, null);
        });

        listener.broadcastRecordedEvent(new EventRecorded(session, event));

        assertThat(drains.get()).isEqualTo(2);
        verify(factory, never()).eventAppended(session, event);
        verify(factory).sessionUpdated(session);
        verify(lineage).linksWhereChild(session.id());
    }

    @Test
    void multipleTransientSubscribersShareOnePayloadConstruction() {
        broadcaster.register();
        broadcaster.register();

        listener.broadcastRecordedEvent(new EventRecorded(session, event));

        verify(factory).eventAppended(session, event);
        verify(factory).sessionUpdated(session);
        verify(lineage).linksWhereChild(session.id());
    }

    @Test
    void sessionUpdateReusesOneLineageReadForParentAndDistinctTypes() {
        when(lineage.linksWhereChild(session.id()))
                .thenReturn(List.of(
                        new SessionLink("first", "parent", "session", LinkType.SPAWNED, now),
                        new SessionLink("second", "other", "session", LinkType.SPAWNED, now)));

        StreamEvents.SessionUpdated updated = factory.sessionUpdated(session);

        assertThat(updated.spawnedBy()).isEqualTo("parent");
        assertThat(updated.linkTypes()).containsExactly("spawned");
        verify(lineage, times(1)).linksWhereChild(session.id());
    }
}
