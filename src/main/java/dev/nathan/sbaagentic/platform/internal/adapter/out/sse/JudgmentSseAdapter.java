package dev.nathan.sbaagentic.platform.internal.adapter.out.sse;

import dev.nathan.sbaagentic.judgment.JudgmentAppended;
import dev.nathan.sbaagentic.judgment.JudgmentPublication;
import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.EventBroadcaster;
import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.StreamEvents;
import org.springframework.stereotype.Component;

@Component
public class JudgmentSseAdapter implements JudgmentPublication {

    private final EventBroadcaster broadcaster;

    public JudgmentSseAdapter(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void judgmentAppended(JudgmentAppended event) {
        broadcaster.publishJudgmentAppended(new StreamEvents.JudgmentAppended(
                event.eventIds(),
                event.sessionId(),
                event.beatId(),
                event.phase(),
                event.salience(),
                event.novelty(),
                event.human(),
                event.kin(),
                event.judge(),
                event.model(),
                event.version(),
                event.judgedAt()));
    }
}
