package dev.nathan.sbaagentic.platform.internal.application.port;

import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.DatabaseFootprint;
import java.util.Map;

public interface StorageInspector {

    DatabaseFootprint footprint();

    /** Row count per ordinary table, sorted by name; virtual tables are excluded. */
    Map<String, Long> tableRows();
}
