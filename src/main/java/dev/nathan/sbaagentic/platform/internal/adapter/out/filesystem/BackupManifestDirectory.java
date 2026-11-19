package dev.nathan.sbaagentic.platform.internal.adapter.out.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.BackupStatus;
import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.ManifestCheck;
import dev.nathan.sbaagentic.platform.internal.application.port.BackupManifestInspector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Structural verification of the sealed manifests that {@code scripts/storage/blackbox_offsite_backup.py}
 * writes. Read-only: it lists one directory, reads manifest JSON, and (on request) hashes archives.
 *
 * <p>The manifest MAC is never checked here. It is keyed by the backup passphrase, and the server
 * process must not hold that secret. A manifest is reported as {@code sealed} when its
 * authentication block has the fixed algorithm and work factor the backup tool requires; whether
 * the MAC is genuine is for the offline {@code restore-test}.
 */
@Component
public class BackupManifestDirectory implements BackupManifestInspector {

    static final String MANIFEST_SUFFIX = ".manifest.json";
    static final String FORMAT = "blackbox-offsite-backup/1";
    static final int MAX_MANIFESTS = 12;
    static final long MAX_MANIFEST_BYTES = 1024 * 1024;

    private static final String AUTH_ALGORITHM = "hmac-sha256";
    private static final String AUTH_KDF = "pbkdf2-hmac-sha256";
    private static final int KDF_ITERATIONS = 600_000;
    private static final String CIPHER = "aes-256-cbc";
    private static final Set<String> COMPRESSIONS = Set.of("zstd", "none");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String directory;
    private final int maxAgeHours;
    private final Clock clock;

    public BackupManifestDirectory(
            @Value("${sba.diagnostics.backup-dir:}") String directory,
            @Value("${sba.diagnostics.backup-max-age-hours:36}") int maxAgeHours,
            Clock clock) {
        this.directory = expandHome(directory == null ? "" : directory.strip());
        this.maxAgeHours = maxAgeHours;
        this.clock = clock;
    }

    /** Expanded here, not in YAML: {@code ${user.home}} is not resolvable in every environment. */
    static String expandHome(String directory) {
        if (!directory.equals("~") && !directory.startsWith("~/")) {

            return directory;
        }
        String home = System.getProperty("user.home", "");

        return home.isEmpty() ? "" : home + directory.substring(1);
    }

    @Override
    public BackupStatus inspect(boolean verifyChecksums) {
        if (directory.isEmpty()) {

            return new BackupStatus("not_configured", false, maxAgeHours, 0, List.of());
        }
        Path root = Path.of(directory);
        if (!Files.isDirectory(root)) {

            return new BackupStatus("missing_directory", false, maxAgeHours, 0, List.of());
        }

        List<Path> manifests;
        try {
            manifests = manifestFiles(root);
        } catch (IOException ex) {

            return new BackupStatus("failed", false, maxAgeHours, 0, List.of());
        }
        if (manifests.isEmpty()) {

            return new BackupStatus("no_manifests", false, maxAgeHours, 0, List.of());
        }

        List<ManifestCheck> checks = new ArrayList<>();
        Set<String> newestPerLabel = new HashSet<>();
        for (Path manifest : manifests.subList(0, Math.min(MAX_MANIFESTS, manifests.size()))) {
            String label = labelOf(manifest);
            // Hashing reads whole archives, so only the newest set per database is rehashed.
            boolean hash = verifyChecksums && newestPerLabel.add(label);
            checks.add(check(root, manifest, hash));
        }

        return new BackupStatus(overall(checks), verifyChecksums, maxAgeHours, manifests.size(), List.copyOf(checks));
    }

    /** Newest first. Names embed a sortable UTC stamp, so name order is creation order per label. */
    private static List<Path> manifestFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + MANIFEST_SUFFIX)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(path);
                }
            }
        }
        files.sort(Comparator.comparing(BackupManifestDirectory::stampOf)
                .thenComparing(path -> path.getFileName().toString())
                .reversed());

        return files;
    }

    private String overall(List<ManifestCheck> checks) {
        Set<String> seen = new HashSet<>();
        boolean stale = false;
        for (ManifestCheck check : checks) {
            if (!seen.add(check.label())) {
                continue;
            }
            // Only the newest manifest per database decides health; older sets are history.
            if (!"ok".equals(check.status())) {

                return "failed";
            }
            if (check.ageHours() == null || check.ageHours() > maxAgeHours) {
                stale = true;
            }
        }

        return stale ? "stale" : "ok";
    }

    private ManifestCheck check(Path root, Path manifest, boolean hash) {
        String name = manifest.getFileName().toString();
        String label = labelOf(manifest);
        List<String> problems = new ArrayList<>();
        JsonNode envelope = read(manifest, problems);
        if (envelope == null) {

            return failed(name, label, problems);
        }

        JsonNode body = envelope.path("manifest");
        JsonNode auth = envelope.path("authentication");
        if (!body.isObject()) {
            problems.add("missing_manifest_body");

            return failed(name, label, problems);
        }
        boolean sealed = auth.path("algorithm").asText().equals(AUTH_ALGORITHM)
                && auth.path("kdf").asText().equals(AUTH_KDF)
                && auth.path("iterations").asInt() == KDF_ITERATIONS
                && auth.path("salt").isTextual()
                && auth.path("mac").isTextual();
        if (!sealed) {
            problems.add("unsealed");
        }
        if (!FORMAT.equals(body.path("format").asText())) {
            problems.add("unsupported_format");
        }
        if (!label.equals(body.path("label").asText())) {
            problems.add("label_mismatch");
        }

        String created =
                body.path("created_utc").isTextual() ? body.path("created_utc").asText() : null;
        Long ageHours = ageHours(created);
        if (ageHours == null) {
            problems.add("invalid_created_utc");
        }

        JsonNode archive = body.path("archive");
        ArchiveCheck archiveCheck = checkArchive(root, archive, hash, problems);

        JsonNode database = body.path("database");
        String integrity = database.path("integrity_check").isTextual()
                ? database.path("integrity_check").asText()
                : null;
        if (!"ok".equals(integrity)) {
            problems.add("integrity_not_ok");
        }
        Integer tables =
                database.path("tables").isObject() ? database.path("tables").size() : null;
        Long rows = database.path("total_rows").canConvertToLong()
                ? database.path("total_rows").asLong()
                : null;

        return new ManifestCheck(
                name,
                label,
                created,
                ageHours,
                problems.isEmpty() ? "ok" : "failed",
                List.copyOf(problems),
                sealed,
                "not_verified",
                archiveCheck.present(),
                archiveCheck.sizeMatches(),
                archiveCheck.checksumMatches(),
                integrity,
                tables,
                rows);
    }

    private record ArchiveCheck(boolean present, Boolean sizeMatches, Boolean checksumMatches) {}

    private static ArchiveCheck checkArchive(Path root, JsonNode archive, boolean hash, List<String> problems) {
        String archiveName =
                archive.path("name").isTextual() ? archive.path("name").asText() : "";
        boolean supported = CIPHER.equals(archive.path("cipher").asText())
                && archive.path("iterations").asInt() == KDF_ITERATIONS
                && COMPRESSIONS.contains(archive.path("compression").asText());
        if (!supported) {
            problems.add("unsupported_archive");
        }
        // A manifest must name a sibling file; anything path-like is refused, never resolved.
        if (archiveName.isEmpty()
                || !archiveName.equals(Path.of(archiveName).getFileName().toString())
                || archiveName.startsWith(".")) {
            problems.add("invalid_archive_name");

            return new ArchiveCheck(false, null, null);
        }
        Path file = root.resolve(archiveName);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            problems.add("archive_missing");

            return new ArchiveCheck(false, null, null);
        }

        Boolean sizeMatches;
        try {
            sizeMatches = archive.path("bytes").canConvertToLong()
                    && Files.size(file) == archive.path("bytes").asLong();
        } catch (IOException ex) {
            sizeMatches = null;
        }
        if (!Boolean.TRUE.equals(sizeMatches)) {
            problems.add("archive_size_mismatch");
        }

        Boolean checksumMatches = null;
        if (hash) {
            String expected = archive.path("sha256").asText("");
            String actual = sha256(file);
            checksumMatches = actual != null && MessageDigest.isEqual(ascii(actual), ascii(expected));
            if (!checksumMatches) {
                problems.add("archive_checksum_mismatch");
            }
        }

        return new ArchiveCheck(true, sizeMatches, checksumMatches);
    }

    private static JsonNode read(Path manifest, List<String> problems) {
        try {
            if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
                problems.add("manifest_too_large");

                return null;
            }
            JsonNode envelope = JSON.readTree(manifest.toFile());
            if (envelope == null || !envelope.isObject()) {
                problems.add("not_json_object");

                return null;
            }

            return envelope;
        } catch (IOException ex) {
            problems.add("unreadable");

            return null;
        }
    }

    private static ManifestCheck failed(String name, String label, List<String> problems) {

        return new ManifestCheck(
                name,
                label,
                null,
                null,
                "failed",
                List.copyOf(problems),
                false,
                "not_verified",
                false,
                null,
                null,
                null,
                null,
                null);
    }

    private Long ageHours(String createdUtc) {
        if (createdUtc == null) {

            return null;
        }
        try {

            return Math.max(
                    0,
                    Duration.between(Instant.parse(createdUtc), clock.instant()).toHours());
        } catch (DateTimeParseException ex) {

            return null;
        }
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {

            return null;
        }
    }

    private static byte[] ascii(String value) {

        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** {@code <label>-<YYYYMMDD-HHMMSS>.manifest.json}; anything else sorts as its whole stem. */
    static String labelOf(Path manifest) {
        String stem = stem(manifest);
        int cut = stem.length() - "-YYYYMMDD-HHMMSS".length();

        return cut > 0 && stem.substring(cut).matches("-\\d{8}-\\d{6}") ? stem.substring(0, cut) : stem;
    }

    private static String stampOf(Path manifest) {
        String stem = stem(manifest);

        return stem.length() > 15 ? stem.substring(stem.length() - 15) : stem;
    }

    private static String stem(Path manifest) {
        String name = manifest.getFileName().toString();

        return name.substring(0, name.length() - MANIFEST_SUFFIX.length());
    }
}
