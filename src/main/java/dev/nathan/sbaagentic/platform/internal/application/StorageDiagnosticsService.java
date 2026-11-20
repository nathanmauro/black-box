package dev.nathan.sbaagentic.platform.internal.application;

import dev.nathan.sbaagentic.memory.MemoryIndexDiagnostics;
import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.Indexes;
import dev.nathan.sbaagentic.platform.internal.application.port.BackupManifestInspector;
import dev.nathan.sbaagentic.platform.internal.application.port.StorageInspector;
import dev.nathan.sbaagentic.recording.RecordingDiagnostics;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Assembles the storage diagnostics snapshot. Every collaborator is read-only. */
@Service
public class StorageDiagnosticsService {

    private final StorageInspector storage;
    private final RecordingDiagnostics recording;
    private final MemoryIndexDiagnostics memory;
    private final BackupManifestInspector backups;
    private final Clock clock;

    public StorageDiagnosticsService(
            StorageInspector storage,
            RecordingDiagnostics recording,
            MemoryIndexDiagnostics memory,
            BackupManifestInspector backups,
            Clock clock) {
        this.storage = storage;
        this.recording = recording;
        this.memory = memory;
        this.backups = backups;
        this.clock = clock;
    }

    public StorageDiagnostics snapshot(boolean verifyChecksums) {

        return new StorageDiagnostics(
                clock.instant(),
                storage.footprint(),
                storage.tableRows(),
                new Indexes(recording.fullTextIndex(), memory.embeddingIndex()),
                recording.redaction(),
                backups.inspect(verifyChecksums));
    }
}
