package dev.nathan.sbaagentic.judgment.internal.adapter.in.web;

import dev.nathan.sbaagentic.judgment.EventJudgment;
import dev.nathan.sbaagentic.judgment.JudgmentOperations;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class JudgmentController {

    private final JudgmentOperations judgments;

    public JudgmentController(JudgmentOperations judgments) {
        this.judgments = judgments;
    }

    @GetMapping("/events/{id}/judgment")
    public ResponseEntity<EventJudgment> eventJudgment(@PathVariable String id) {

        return ResponseEntity.of(judgments.findByEventId(id));
    }

    @GetMapping("/sessions/{sessionId}/judgments")
    public List<EventJudgment> sessionJudgments(
            @PathVariable String sessionId, @RequestParam(defaultValue = "100") int limit) {

        return judgments.findForSession(sessionId, Math.max(1, Math.min(limit, 250)));
    }
}
