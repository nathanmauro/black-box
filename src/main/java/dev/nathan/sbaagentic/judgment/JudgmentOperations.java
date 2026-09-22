package dev.nathan.sbaagentic.judgment;

import java.util.List;
import java.util.Optional;

public interface JudgmentOperations {

    Optional<EventJudgment> findByEventId(String eventId);

    List<EventJudgment> findForSession(String sessionId, int limit);
}
