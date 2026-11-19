package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics;
import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnosticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only storage health: database footprint, row counts, index parity, redaction counters, and
 * backup manifest checks. GET only; nothing here writes, checkpoints, or repairs.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class StorageDiagnosticsController {

    private final StorageDiagnosticsService diagnostics;

    public StorageDiagnosticsController(StorageDiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** {@code verifyChecksums=true} rehashes the newest archive per database: slow on big backups. */
    @GetMapping("/storage")
    public StorageDiagnostics storage(@RequestParam(defaultValue = "false") boolean verifyChecksums) {

        return diagnostics.snapshot(verifyChecksums);
    }
}
