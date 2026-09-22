package dev.nathan.sbaagentic.project.internal.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ProjectGraphStore {

    List<CaptureRow> recentCaptures(String canonicalKey, int limit);

    List<TaskRow> openTasks(String canonicalKey, int limit);

    long totalCaptures(String canonicalKey);

    record CaptureRow(
            String id,
            String sourceType,
            String eventType,
            String sessionId,
            String sessionTitle,
            String clientSessionId,
            String source,
            String headline,
            String text,
            Map<String, Object> metadata,
            Instant observedAt) {}

    record TaskRow(String id, String title, String status, int priority, Instant updatedAt) {}
}
