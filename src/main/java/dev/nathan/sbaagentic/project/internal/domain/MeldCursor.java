package dev.nathan.sbaagentic.project.internal.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Versioned, scope-specific page boundary; contains no offset or database-dependent timestamp conversion. */
public record MeldCursor(Instant createdAt, String id) {
    public static MeldCursor parse(String value) {
        if (value == null)

            return null;

        try {
            if (value.length() > 1024 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (!Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(decoded.getBytes(StandardCharsets.UTF_8))
                    .equals(value)) throw new IllegalArgumentException();
            String[] parts = decoded.split("\n", -1);
            if (parts.length != 4
                    || !parts[0].equals("braid-unassigned-v1")
                    || !parts[3].isEmpty()
                    || parts[2].isBlank()
                    || parts[2].length() > 256
                    || parts[2].chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();

            return new MeldCursor(Instant.parse(parts[1]), parts[2]);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid before cursor for unassigned braids.");
        }
    }

    public String encoded() {
        String payload = "braid-unassigned-v1\n" + createdAt + "\n" + id + "\n";

        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
