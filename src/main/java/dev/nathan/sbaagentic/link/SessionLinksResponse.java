package dev.nathan.sbaagentic.link;

import java.util.List;

public record SessionLinksResponse(
        List<SessionLinkView> parents,
        List<SessionLinkView> children) {
}
