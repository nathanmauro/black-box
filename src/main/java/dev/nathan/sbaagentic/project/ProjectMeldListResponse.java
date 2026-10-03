package dev.nathan.sbaagentic.project;

import java.util.List;

public record ProjectMeldListResponse(List<ProjectSavedMeld> items, int count, String nextBefore) {}
