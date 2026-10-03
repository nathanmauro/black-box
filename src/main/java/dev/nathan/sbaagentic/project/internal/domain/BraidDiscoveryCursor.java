package dev.nathan.sbaagentic.project.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Independent from the unassigned REST cursor; binds selectors and all-saved-braid coverage. */
public record BraidDiscoveryCursor(Instant createdAt, String id, String fingerprint) {
    public static String fingerprint(String query, String sessionId) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update("saved_braids_all_ownership:v1".getBytes(StandardCharsets.UTF_8));
            for (String value : new String[] {query, sessionId}) {
                hash.update((byte) (value == null ? 0 : 1));
                if (value != null) {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    hash.update(bytes);
                }
            }

            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static BraidDiscoveryCursor parse(String value, String fingerprint) {
        if (value == null)

            return null;

        try {
            if (value.length() > 131072) throw new IllegalArgumentException();
            String[] parts = decode(value).split("\n", -1);
            if (parts.length != 5
                    || !parts[0].equals("braid-discovery-v1")
                    || !parts[1].equals(fingerprint)
                    || !parts[4].isEmpty()) throw new IllegalArgumentException();
            String id = decode(parts[3]);
            if (id.isBlank() || id.indexOf(0) >= 0) throw new IllegalArgumentException();

            return new BraidDiscoveryCursor(Instant.parse(parts[2]), id, fingerprint);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid before cursor or changed braid discovery filters.");
        }
    }

    public String encoded() {

        return encode("braid-discovery-v1\n" + fingerprint + "\n" + createdAt + "\n" + encode(id) + "\n");
    }

    private static String encode(String text) {

        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        if (!encode(decoded).equals(value)) throw new IllegalArgumentException();

        return decoded;
    }
}
