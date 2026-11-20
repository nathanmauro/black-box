package dev.nathan.sbaagentic.platform.internal.application.port;

import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.BackupStatus;

public interface BackupManifestInspector {

    /** {@code verifyChecksums} rehashes archives, which reads every archive byte. */
    BackupStatus inspect(boolean verifyChecksums);
}
