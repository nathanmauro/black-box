package dev.nathan.sbaagentic.platform.internal.application;

import dev.nathan.sbaagentic.memory.EmbeddingIndexStatus;
import dev.nathan.sbaagentic.recording.FullTextIndexStatus;
import dev.nathan.sbaagentic.recording.RedactionStatistics;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One read-only snapshot of storage health. Sanitized by construction: file names but never
 * directories, counts but never content, and no hash, salt, MAC, or configured value.
 */
public record StorageDiagnostics(
        Instant generatedAt,
        DatabaseFootprint database,
        Map<String, Long> tableRows,
        Indexes indexes,
        RedactionStatistics redaction,
        BackupStatus backups) {

    /**
     * @param backend {@code sqlite} or {@code postgres}
     * @param fileName the main database file's name only; {@code null} for a server database
     * @param walBytes size of the {@code -wal} file, {@code 0} when absent
     */
    public record DatabaseFootprint(
            String backend,
            String fileName,
            Long fileBytes,
            Long walBytes,
            Long shmBytes,
            String journalMode,
            Long pageSize,
            Long pageCount,
            Long freelistPages) {}

    public record Indexes(FullTextIndexStatus fullText, EmbeddingIndexStatus embeddings) {}

    /**
     * @param status {@code ok}, {@code stale}, {@code failed}, {@code no_manifests},
     *     {@code missing_directory}, or {@code not_configured}
     * @param checksumsVerified whether archive SHA-256 values were recomputed on this request
     * @param manifests the newest manifests first, at most a bounded number
     */
    public record BackupStatus(
            String status,
            boolean checksumsVerified,
            Integer maxAgeHours,
            int manifestsFound,
            List<ManifestCheck> manifests) {}

    /**
     * Structural verification of one sealed manifest. {@code authentication} is always
     * {@code not_verified}: the HMAC needs the backup passphrase, which the server deliberately
     * never reads. Run the offline {@code restore-test} for cryptographic verification.
     *
     * @param status {@code ok} or {@code failed}
     * @param problems stable problem codes, empty when {@code ok}
     * @param archiveChecksumMatches {@code null} unless checksums were verified
     */
    public record ManifestCheck(
            String manifest,
            String label,
            String createdUtc,
            Long ageHours,
            String status,
            List<String> problems,
            boolean sealed,
            String authentication,
            boolean archivePresent,
            Boolean archiveSizeMatches,
            Boolean archiveChecksumMatches,
            String databaseIntegrity,
            Integer databaseTables,
            Long databaseRows) {}
}
