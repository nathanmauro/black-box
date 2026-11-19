package dev.nathan.sbaagentic.platform.internal.adapter.out.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Writes manifests in the exact envelope {@code blackbox_offsite_backup.py} produces. The MAC and
 * salt are fixed dummy hex: the server never verifies them, and tests assert they never leak.
 */
public final class BackupManifestFixtures {

    public static final String SALT = "5a17c0ffee5a17c0ffee5a17c0ffee5a";
    public static final String MAC = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private BackupManifestFixtures() {}

    /** Archive bytes plus a sealed manifest beside it; returns the manifest path. */
    public static Path write(Path directory, String label, Instant created, Consumer<ObjectNode> customize)
            throws IOException {
        String base = label + "-" + STAMP.format(created);
        byte[] archiveBytes = ("encrypted archive for " + base).getBytes(StandardCharsets.UTF_8);
        String archiveName = base + ".sqlite3.zst.enc";
        Files.write(directory.resolve(archiveName), archiveBytes);

        ObjectNode envelope = JSON.createObjectNode();
        ObjectNode body = envelope.putObject("manifest");
        body.put("format", "blackbox-offsite-backup/1");
        body.put("label", label);
        body.put("stamp", STAMP.format(created));
        body.put(
                "created_utc",
                created.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        ObjectNode archive = body.putObject("archive");
        archive.put("name", archiveName);
        archive.put("bytes", archiveBytes.length);
        archive.put("sha256", sha256(archiveBytes));
        archive.put("cipher", "aes-256-cbc");
        archive.put("kdf", "pbkdf2-hmac-sha256");
        archive.put("iterations", 600_000);
        archive.put("compression", "zstd");
        ObjectNode database = body.putObject("database");
        database.put("integrity_check", "ok");
        database.put("schema_sha256", sha256("schema".getBytes(StandardCharsets.UTF_8)));
        ObjectNode tables = database.putObject("tables");
        tables.put("agent_events", 40);
        tables.put("agent_sessions", 2);
        database.put("total_rows", 42);
        database.put("bytes", 4096);
        database.put("sha256", sha256("database".getBytes(StandardCharsets.UTF_8)));
        ObjectNode auth = envelope.putObject("authentication");
        auth.put("algorithm", "hmac-sha256");
        auth.put("kdf", "pbkdf2-hmac-sha256");
        auth.put("iterations", 600_000);
        auth.put("salt", SALT);
        auth.put("mac", MAC);

        customize.accept(envelope);
        Path manifest = directory.resolve(base + ".manifest.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), envelope);

        return manifest;
    }

    public static Path write(Path directory, String label, Instant created) throws IOException {

        return write(directory, label, created, envelope -> {});
    }

    public static String sha256(byte[] bytes) {
        try {

            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
