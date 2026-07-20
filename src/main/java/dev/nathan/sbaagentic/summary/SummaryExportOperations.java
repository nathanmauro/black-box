package dev.nathan.sbaagentic.summary;

import java.util.List;

public interface SummaryExportOperations {

    List<ExportTarget> targets();

    SummaryExport exportSummary(String sessionId, String targetId);
}
