package dev.nathan.sbaagentic.judgment.internal.application.port;

import dev.nathan.sbaagentic.judgment.EventJudgment;
import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import java.util.List;
import java.util.Optional;

public interface JudgmentRepository {

    void saveForBeat(Beat beat, Judgment judgment);

    Optional<EventJudgment> findByEventId(String eventId);

    List<EventJudgment> findForSession(String sessionId, int limit);
}
