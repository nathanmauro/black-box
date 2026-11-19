package dev.nathan.sbaagentic.recording.internal.application.port;

import dev.nathan.sbaagentic.recording.FullTextIndexStatus;

public interface FullTextIndexInspector {

    FullTextIndexStatus inspect();
}
