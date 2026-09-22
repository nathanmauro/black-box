package dev.nathan.sbaagentic.judgment.internal.application;

import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.judgment.EventJudgment;
import dev.nathan.sbaagentic.judgment.JudgmentOperations;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgmentRepository;

import org.springframework.stereotype.Service;

@Service
public class JudgmentService implements JudgmentOperations {

    private final JudgmentRepository repository;

    public JudgmentService(JudgmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<EventJudgment> findByEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByEventId(eventId);
    }

    @Override
    public List<EventJudgment> findForSession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return repository.findForSession(sessionId, Math.max(1, Math.min(limit, 250)));
    }
}
