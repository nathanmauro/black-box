package dev.nathan.sbaagentic.project.internal.adapter.in.web;

import dev.nathan.sbaagentic.project.internal.application.BraidGraph;
import dev.nathan.sbaagentic.project.internal.application.BraidGraphExportService;
import dev.nathan.sbaagentic.project.internal.application.BraidGraphMermaid;
import dev.nathan.sbaagentic.project.internal.application.ContinuityLintService;
import dev.nathan.sbaagentic.project.internal.application.ContinuityReport;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only continuity surfaces: a lint over decisions, handoffs, observations and braids, and a
 * redacted braid-graph export. GET only; nothing here writes or repairs.
 */
@RestController
public class ContinuityController {

    private static final MediaType MERMAID = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final ContinuityLintService lint;
    private final BraidGraphExportService export;

    public ContinuityController(ContinuityLintService lint, BraidGraphExportService export) {
        this.lint = lint;
        this.export = export;
    }

    @GetMapping("/api/diagnostics/continuity")
    public ContinuityReport continuity(
            @RequestParam(required = false) String project,
            @RequestParam(defaultValue = "168") int staleAfterHours,
            @RequestParam(defaultValue = "100") int limit) {

        return lint.lint(project, staleAfterHours, limit);
    }

    /** {@code format=json} (default) returns the graph; {@code format=mermaid} a flowchart. */
    @GetMapping("/api/export/braid-graph")
    public ResponseEntity<?> braidGraph(
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(required = false) String project,
            @RequestParam(defaultValue = "50") int maxSessions) {
        String normalized = format.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals("json") && !normalized.equals("mermaid")) {
            throw new IllegalArgumentException("format must be json or mermaid.");
        }
        BraidGraph graph = export.export(project, maxSessions);
        if (normalized.equals("json")) {

            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(graph);
        }

        return ResponseEntity.ok().contentType(MERMAID).body(BraidGraphMermaid.render(graph));
    }
}
