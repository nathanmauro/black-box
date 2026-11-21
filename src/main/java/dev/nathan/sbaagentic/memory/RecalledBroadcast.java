package dev.nathan.sbaagentic.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The compact form of an active broadcast that recall attaches for the resolved project. It omits
 * provenance on purpose: recall output lands in a session's opening context, so every field here
 * has to earn its characters. {@code listBroadcasts} returns the full record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecalledBroadcast(
        String key,
        String kind,
        String severity,
        String message,
        Instant updatedAt,
        Instant expiresAt,
        Instant followUpAt) {

    public static RecalledBroadcast of(ProjectBroadcast broadcast) {

        return new RecalledBroadcast(
                broadcast.key(),
                broadcast.kind(),
                broadcast.severity(),
                broadcast.message(),
                broadcast.updatedAt(),
                broadcast.expiresAt(),
                broadcast.followUpAt());
    }
}
