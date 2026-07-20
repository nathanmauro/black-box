package dev.nathan.sbaagentic.recording;

import java.util.List;

public record DashboardStats(
        long totalSessions,
        long totalEvents,
        List<BreakdownCount> eventsBySource,
        List<BreakdownCount> eventsByKind,
        List<BreakdownCount> sessionsBySource,
        List<DailyCount> recentActivity) {

    public record BreakdownCount(String name, long count) {
    }

    public record DailyCount(String day, long count) {
    }
}
