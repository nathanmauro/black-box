package dev.nathan.sbaagentic.workflow;

import java.util.List;

public record SessionLinksResponse(
        List<SessionLinkView> parents,
        List<SessionLinkView> children) {
}
