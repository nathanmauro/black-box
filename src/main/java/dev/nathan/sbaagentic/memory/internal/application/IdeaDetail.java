package dev.nathan.sbaagentic.memory.internal.application;

import java.util.List;

public record IdeaDetail(IdeaView idea, List<EvidenceView> supports, List<EvidenceView> refutes) {}
