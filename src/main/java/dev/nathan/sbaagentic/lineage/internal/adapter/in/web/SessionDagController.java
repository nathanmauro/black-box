package dev.nathan.sbaagentic.lineage.internal.adapter.in.web;

import dev.nathan.sbaagentic.lineage.DagOperations;
import dev.nathan.sbaagentic.lineage.DagResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionDagController {
    private final DagOperations dags;

    public SessionDagController(DagOperations dags) {
        this.dags = dags;
    }

    @GetMapping("/api/dag")
    public DagResponse dag(@RequestParam String sessionId) {

        return dags.forSession(sessionId);
    }
}
