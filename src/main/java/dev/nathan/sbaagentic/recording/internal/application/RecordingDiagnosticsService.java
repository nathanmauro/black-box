package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.FullTextIndexStatus;
import dev.nathan.sbaagentic.recording.RecordingDiagnostics;
import dev.nathan.sbaagentic.recording.RedactionStatistics;
import dev.nathan.sbaagentic.recording.internal.application.port.FullTextIndexInspector;
import org.springframework.stereotype.Service;

@Service
public class RecordingDiagnosticsService implements RecordingDiagnostics {

    private final FullTextIndexInspector fullTextIndex;
    private final RedactionService redaction;

    public RecordingDiagnosticsService(FullTextIndexInspector fullTextIndex, RedactionService redaction) {
        this.fullTextIndex = fullTextIndex;
        this.redaction = redaction;
    }

    @Override
    public FullTextIndexStatus fullTextIndex() {

        return fullTextIndex.inspect();
    }

    @Override
    public RedactionStatistics redaction() {

        return redaction.statistics();
    }
}
