package dev.nathan.sbaagentic.lineage;

import java.util.List;

public record SessionLinksResponse(List<SessionLinkView> parents, List<SessionLinkView> children) {}
