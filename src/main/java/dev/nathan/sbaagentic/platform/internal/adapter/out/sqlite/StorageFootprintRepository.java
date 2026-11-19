package dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.platform.internal.application.StorageDiagnostics.DatabaseFootprint;
import dev.nathan.sbaagentic.platform.internal.application.port.StorageInspector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Database file sizes, PRAGMA readings, and per-table row counts. Every statement is a read: no
 * checkpoint, no VACUUM, no ANALYZE. Table selection mirrors the offline backup manifest (every
 * ordinary table, virtual tables skipped), so live counts and manifest counts line up.
 */
@Repository
public class StorageFootprintRepository implements StorageInspector {

    private final JdbcTemplate jdbc;
    private final boolean sqlite;

    public StorageFootprintRepository(JdbcTemplate jdbc, @Value("${sba.storage.backend:sqlite}") String backend) {
        this.jdbc = jdbc;
        this.sqlite = !"postgres".equals(backend);
    }

    @Override
    public DatabaseFootprint footprint() {
        if (!sqlite) {

            return new DatabaseFootprint("postgres", null, null, null, null, null, null, null, null);
        }
        Path file = mainDatabaseFile();

        return new DatabaseFootprint(
                "sqlite",
                file == null ? null : file.getFileName().toString(),
                file == null ? null : size(file),
                file == null ? null : sizeOrZero(sibling(file, "-wal")),
                file == null ? null : sizeOrZero(sibling(file, "-shm")),
                jdbc.queryForObject("PRAGMA journal_mode", String.class),
                pragma("page_size"),
                pragma("page_count"),
                pragma("freelist_count"));
    }

    @Override
    public Map<String, Long> tableRows() {
        Map<String, Long> rows = new TreeMap<>();
        for (String table : ordinaryTables()) {
            rows.put(table, jdbc.queryForObject("SELECT count(*) FROM " + quote(table), Long.class));
        }

        return rows;
    }

    private List<String> ordinaryTables() {
        if (!sqlite) {

            return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                """, String.class);
        }
        List<String> tables = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'");
        for (Map<String, Object> row : rows) {
            // A virtual table needs its module loaded to count; its shadow tables are ordinary.
            if (!isVirtual((String) row.get("sql"))) {
                tables.add((String) row.get("name"));
            }
        }

        return tables;
    }

    private static boolean isVirtual(String sql) {

        return sql != null && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE");
    }

    private Path mainDatabaseFile() {
        List<Map<String, Object>> databases = jdbc.queryForList("PRAGMA database_list");
        for (Map<String, Object> database : databases) {
            Object file = database.get("file");
            if ("main".equals(database.get("name")) && file instanceof String path && !path.isBlank()) {

                return Path.of(path);
            }
        }

        // In-memory or temporary database: there is no file to measure.
        return null;
    }

    private Long pragma(String name) {

        return jdbc.queryForObject("PRAGMA " + name, Long.class);
    }

    private static Path sibling(Path file, String suffix) {

        return file.resolveSibling(file.getFileName() + suffix);
    }

    private static Long size(Path file) {
        try {

            return Files.size(file);
        } catch (java.io.IOException ex) {

            return null;
        }
    }

    private static long sizeOrZero(Path file) {
        Long size = Files.exists(file) ? size(file) : null;

        return size == null ? 0 : size;
    }

    private static String quote(String identifier) {

        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
