package dev.nathan.sbaagentic.project;

import java.util.List;

/** Outbound discovery projection; stored artifacts and caller metadata are never rewritten. */
public record BraidDiscoveryResult(
        String status,
        String coverage,
        int count,
        List<Hit> items,
        String nextBefore,
        boolean truncated,
        int maxBytes,
        List<String> diagnostics) {
    public record Hit(
            String artifactId,
            String artifactKind,
            String sourceType,
            String createdAt,
            String ownership,
            String projectKey,
            String canonicalKey,
            String title,
            String body,
            String provider,
            String model,
            String providerBasis,
            List<Source> sessions,
            String detailPath,
            boolean titleTruncated,
            boolean bodyTruncated,
            boolean provenanceTruncated,
            boolean transformed,
            boolean referenceOnly,
            boolean textComplete) {}

    public record Source(
            String sessionId,
            String source,
            String clientSessionId,
            String cwd,
            String provenanceBasis,
            boolean transformed,
            boolean truncated) {}
}
