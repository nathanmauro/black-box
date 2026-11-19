package dev.nathan.sbaagentic.platform.internal.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.BackupStatus;
import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.ManifestCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Backup manifest directory checks sealed manifests without holding the passphrase")
class BackupManifestDirectoryTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path dir;

    private BackupManifestDirectory inspector() {

        return new BackupManifestDirectory(dir.toString(), 36, CLOCK);
    }

    @Test
    void blankDirectoryIsNotConfigured() {
        BackupStatus status = new BackupManifestDirectory("  ", 36, CLOCK).inspect(false);

        assertThat(status.status()).isEqualTo("not_configured");
        assertThat(status.manifests()).isEmpty();
    }

    @Test
    void absentDirectoryIsReportedNotCreated() {
        Path absent = dir.resolve("never-created");
        BackupStatus status = new BackupManifestDirectory(absent.toString(), 36, CLOCK).inspect(false);

        assertThat(status.status()).isEqualTo("missing_directory");
        assertThat(absent).doesNotExist();
    }

    @Test
    void emptyDirectoryHasNoManifests() {
        assertThat(inspector().inspect(false).status()).isEqualTo("no_manifests");
    }

    @Test
    void freshSealedManifestWithMatchingArchiveIsOk() throws IOException {
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(3, ChronoUnit.HOURS));

        BackupStatus status = inspector().inspect(true);

        assertThat(status.status()).isEqualTo("ok");
        assertThat(status.checksumsVerified()).isTrue();
        ManifestCheck check = status.manifests().getFirst();
        assertThat(check.label()).isEqualTo("blackbox-nightly");
        assertThat(check.status()).isEqualTo("ok");
        assertThat(check.problems()).isEmpty();
        assertThat(check.sealed()).isTrue();
        assertThat(check.authentication()).isEqualTo("not_verified");
        assertThat(check.archivePresent()).isTrue();
        assertThat(check.archiveSizeMatches()).isTrue();
        assertThat(check.archiveChecksumMatches()).isTrue();
        assertThat(check.ageHours()).isEqualTo(3L);
        assertThat(check.databaseIntegrity()).isEqualTo("ok");
        assertThat(check.databaseTables()).isEqualTo(2);
        assertThat(check.databaseRows()).isEqualTo(42L);
    }

    @Test
    void checksumsAreOnlyComputedOnRequest() throws IOException {
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(1, ChronoUnit.HOURS));

        ManifestCheck check = inspector().inspect(false).manifests().getFirst();

        assertThat(check.archiveChecksumMatches()).isNull();
        assertThat(check.status()).isEqualTo("ok");
    }

    @Test
    void oldNewestManifestIsStale() throws IOException {
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(50, ChronoUnit.HOURS));

        assertThat(inspector().inspect(false).status()).isEqualTo("stale");
    }

    @Test
    void sameSizeTamperedArchiveFailsOnlyTheChecksum() throws IOException {
        Path manifest = BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(1, ChronoUnit.HOURS));
        Path archive = archiveBeside(manifest);
        byte[] bytes = Files.readAllBytes(archive);
        bytes[0] ^= 0x01;
        Files.write(archive, bytes);

        assertThat(inspector().inspect(false).status()).isEqualTo("ok");
        BackupStatus verified = inspector().inspect(true);
        assertThat(verified.status()).isEqualTo("failed");
        assertThat(verified.manifests().getFirst().problems()).containsExactly("archive_checksum_mismatch");
    }

    @Test
    void truncatedOrMissingArchiveFails() throws IOException {
        Path truncated = BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(2, ChronoUnit.HOURS));
        Files.write(archiveBeside(truncated), new byte[] {1, 2, 3});
        Path missing = BackupManifestFixtures.write(dir, "jobscout-nightly", NOW.minus(2, ChronoUnit.HOURS));
        Files.delete(archiveBeside(missing));

        Map<String, ManifestCheck> byLabel = byLabel(inspector().inspect(false));

        assertThat(byLabel.get("blackbox-nightly").problems()).containsExactly("archive_size_mismatch");
        assertThat(byLabel.get("jobscout-nightly").problems()).containsExactly("archive_missing");
        assertThat(byLabel.get("jobscout-nightly").archivePresent()).isFalse();
    }

    @Test
    void unsealedOrWeakenedAuthenticationIsRejected() throws IOException {
        BackupManifestFixtures.write(
                dir, "no-auth", NOW.minus(1, ChronoUnit.HOURS), envelope -> envelope.remove("authentication"));
        BackupManifestFixtures.write(
                dir,
                "weak-kdf",
                NOW.minus(1, ChronoUnit.HOURS),
                envelope -> ((ObjectNode) envelope.get("authentication")).put("iterations", 1));

        Map<String, ManifestCheck> byLabel = byLabel(inspector().inspect(false));

        assertThat(byLabel.get("no-auth").problems()).containsExactly("unsealed");
        assertThat(byLabel.get("no-auth").sealed()).isFalse();
        assertThat(byLabel.get("weak-kdf").problems()).containsExactly("unsealed");
    }

    @Test
    void pathLikeArchiveNamesAreRefusedWithoutBeingResolved() throws IOException {
        Path outside = dir.getParent().resolve("outside-" + dir.getFileName() + ".enc");
        Files.writeString(outside, "not yours");
        try {
            for (String name : List.of("../" + outside.getFileName(), outside.toString(), "..", ".hidden")) {
                Path manifest = BackupManifestFixtures.write(
                        dir,
                        "bad-name",
                        NOW.minus(1, ChronoUnit.HOURS),
                        envelope -> ((ObjectNode) envelope.get("manifest").get("archive")).put("name", name));

                ManifestCheck check = inspector().inspect(true).manifests().getFirst();

                assertThat(check.problems()).as(name).contains("invalid_archive_name");
                assertThat(check.archivePresent()).as(name).isFalse();
                assertThat(check.archiveChecksumMatches()).as(name).isNull();
                Files.delete(manifest);
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void malformedOversizedAndBodylessManifestsFailWithStableCodes() throws IOException {
        Files.writeString(dir.resolve("broken-20261119-010000.manifest.json"), "{ not json");
        Files.writeString(dir.resolve("array-20261119-010000.manifest.json"), "[]");
        Files.writeString(dir.resolve("bodyless-20261119-010000.manifest.json"), "{\"authentication\":{}}");
        Files.write(
                dir.resolve("huge-20261119-010000.manifest.json"),
                new byte[(int) BackupManifestDirectory.MAX_MANIFEST_BYTES + 1]);

        Map<String, ManifestCheck> byLabel = byLabel(inspector().inspect(false));

        assertThat(byLabel.get("broken").problems()).containsExactly("unreadable");
        assertThat(byLabel.get("array").problems()).containsExactly("not_json_object");
        assertThat(byLabel.get("bodyless").problems()).containsExactly("missing_manifest_body");
        assertThat(byLabel.get("huge").problems()).containsExactly("manifest_too_large");
    }

    @Test
    void wrongFormatLabelIntegrityAndTimestampAreEachReported() throws IOException {
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(1, ChronoUnit.HOURS), envelope -> {
            ObjectNode body = (ObjectNode) envelope.get("manifest");
            body.put("format", "something-else/9");
            body.put("label", "another-label");
            body.put("created_utc", "yesterday");
            ((ObjectNode) body.get("database")).put("integrity_check", "*** corrupt page 7");
        });

        ManifestCheck check = inspector().inspect(false).manifests().getFirst();

        assertThat(check.problems())
                .containsExactlyInAnyOrder(
                        "unsupported_format", "label_mismatch", "invalid_created_utc", "integrity_not_ok");
    }

    @Test
    void onlyTheNewestManifestPerLabelDecidesHealth() throws IOException {
        Path older = BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(30, ChronoUnit.HOURS));
        Files.delete(archiveBeside(older));
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(6, ChronoUnit.HOURS));

        BackupStatus status = inspector().inspect(false);

        assertThat(status.status()).isEqualTo("ok");
        assertThat(status.manifests()).extracting(ManifestCheck::status).containsExactly("ok", "failed");
    }

    @Test
    void checksumsAreRecomputedOnlyForTheNewestSetPerLabel() throws IOException {
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(30, ChronoUnit.HOURS));
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(6, ChronoUnit.HOURS));

        List<ManifestCheck> checks = inspector().inspect(true).manifests();

        assertThat(checks).extracting(ManifestCheck::archiveChecksumMatches).containsExactly(true, null);
    }

    @Test
    void listingIsBoundedAndSymlinkedManifestsAreIgnored() throws IOException {
        for (int i = 0; i < BackupManifestDirectory.MAX_MANIFESTS + 3; i++) {
            BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(i + 1, ChronoUnit.HOURS));
        }
        Path target = BackupManifestFixtures.write(Files.createDirectory(dir.resolve("elsewhere")), "linked", NOW);
        Files.createSymbolicLink(dir.resolve("linked-20261119-120000.manifest.json"), target);

        BackupStatus status = inspector().inspect(false);

        assertThat(status.manifestsFound()).isEqualTo(BackupManifestDirectory.MAX_MANIFESTS + 3);
        assertThat(status.manifests()).hasSize(BackupManifestDirectory.MAX_MANIFESTS);
        assertThat(status.manifests()).extracting(ManifestCheck::label).containsOnly("blackbox-nightly");
        assertThat(status.manifests().getFirst().ageHours()).isEqualTo(1L);
    }

    @Test
    void inspectionNeverWritesToTheBackupDirectory() throws IOException {
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(2, ChronoUnit.HOURS));
        Map<Path, FileTime> before = snapshot();

        inspector().inspect(true);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void reportNeverCarriesSecretsHashesOrDirectories() throws IOException {
        BackupManifestFixtures.write(dir, "blackbox-nightly", NOW.minus(2, ChronoUnit.HOURS));

        String json = new ObjectMapper().writeValueAsString(inspector().inspect(true));

        assertThat(json)
                .doesNotContain(BackupManifestFixtures.SALT)
                .doesNotContain(BackupManifestFixtures.MAC)
                .doesNotContain(dir.toString())
                .doesNotContainPattern("[0-9a-f]{64}");
    }

    @Test
    void leadingTildeIsTheServiceUsersHome() {
        String home = System.getProperty("user.home");

        assertThat(BackupManifestDirectory.expandHome("~/backups")).isEqualTo(home + "/backups");
        assertThat(BackupManifestDirectory.expandHome("~")).isEqualTo(home);
        assertThat(BackupManifestDirectory.expandHome("/abs/~/x")).isEqualTo("/abs/~/x");
        assertThat(BackupManifestDirectory.expandHome("~other/x")).isEqualTo("~other/x");
    }

    @Test
    void labelParsingFollowsTheBackupNamingScheme() {
        assertThat(BackupManifestDirectory.labelOf(Path.of("blackbox-nightly-20261119-031500.manifest.json")))
                .isEqualTo("blackbox-nightly");
        assertThat(BackupManifestDirectory.labelOf(Path.of("odd.manifest.json")))
                .isEqualTo("odd");
    }

    private static Path archiveBeside(Path manifest) {
        String name = manifest.getFileName().toString().replace(".manifest.json", ".sqlite3.zst.enc");

        return manifest.resolveSibling(name);
    }

    private static Map<String, ManifestCheck> byLabel(BackupStatus status) {
        Map<String, ManifestCheck> checks = new HashMap<>();
        for (ManifestCheck check : status.manifests()) {
            checks.putIfAbsent(check.label(), check);
        }

        return checks;
    }

    private Map<Path, FileTime> snapshot() throws IOException {
        Map<Path, FileTime> times = new HashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.toList()) {
                times.put(file, Files.getLastModifiedTime(file));
            }
        }

        return times;
    }
}
