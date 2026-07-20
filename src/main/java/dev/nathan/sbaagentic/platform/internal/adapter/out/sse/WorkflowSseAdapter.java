package dev.nathan.sbaagentic.platform.internal.adapter.out.sse;

import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.EventBroadcaster;
import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.StreamEvents;
import dev.nathan.sbaagentic.workflow.WorkflowPublication;
import dev.nathan.sbaagentic.workflow.WorkflowTaskChanged;
import dev.nathan.sbaagentic.workflow.WorkflowTaskNoted;

import org.springframework.stereotype.Component;

/** Platform-owned translation from workflow publications to the existing SSE wire contract. */
@Component
public class WorkflowSseAdapter implements WorkflowPublication {

    private final EventBroadcaster broadcaster;

    public WorkflowSseAdapter(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void taskChanged(WorkflowTaskChanged event) {
        broadcaster.publishTaskChanged(new StreamEvents.TaskChanged(
                event.task(),
                event.transitionId(),
                event.transitionType(),
                event.observedAt()));
    }

    @Override
    public void taskNoted(WorkflowTaskNoted event) {
        broadcaster.publishTaskNote(new StreamEvents.TaskNoted(
                event.task(), event.annotation(), event.observedAt()));
    }
}
