package dev.nathan.sbaagentic.memory.internal.application;

import java.util.List;

public record EvidenceListResponse(List<EvidenceView> items, int count) {}
