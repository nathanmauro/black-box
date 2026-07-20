package dev.nathan.sbaagentic.summary.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.ai.SessionSummaryService;
import dev.nathan.sbaagentic.exporting.SummaryExportService;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SummaryController {

    private final SessionSummaryService summaryService;
    private final SummaryExportService summaryExportService;

    public SummaryController(
            SessionSummaryService summaryService,
            SummaryExportService summaryExportService) {
        this.summaryService = summaryService;
        this.summaryExportService = summaryExportService;
    }

    @PostMapping("/sessions/{sessionId}/summarize")
    public AgentSession summarize(@PathVariable String sessionId) {
        return summaryService.summarize(sessionId);
    }

    @PostMapping("/sessions/summarize")
    public AgentSession summarizeByClientSession(
            @RequestParam String source,
            @RequestParam String clientSessionId) {
        return summaryService.summarize(source, clientSessionId);
    }

    @PostMapping("/sessions/summarize-missing")
    public SessionSummaryService.SummaryBackfillResult summarizeMissing(
            @RequestParam(defaultValue = "10") int limit) {
        return summaryService.summarizeMissing(limit);
    }

    @GetMapping("/exports/targets")
    public List<SummaryExportService.ExportTarget> exportTargets() {
        return summaryExportService.targets();
    }

    @PostMapping("/sessions/{sessionId}/exports/{targetId}")
    public SummaryExportService.SummaryExport exportSummary(
            @PathVariable String sessionId,
            @PathVariable String targetId) {
        return summaryExportService.exportSummary(sessionId, targetId);
    }
}
