package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdrExportPlannerTest {

    @TempDir
    Path tempDir;

    @Test
    void ordersDecisionsChronologicallyDetectsReferencedFilesAndWritesMarkdownWhenApplied() throws Exception {
        Path project = tempDir.resolve("project");
        Path srcDir = project.resolve("src/main");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Storage.java"), "class Storage {}", StandardCharsets.UTF_8);
        Path outputDir = project.resolve("docs/adr");

        RecalledItem older = new RecalledItem(
                "evt-1",
                "sess-1",
                "decision",
                "claude",
                "client-1",
                project.toString(),
                Instant.parse("2026-09-01T10:00:00Z"),
                "Use SQLite WAL mode in src/main/Storage.java",
                "Keep local setup single-file and fast.",
                List.of("External Postgres by default"),
                0.9,
                List.of("Verify busy_timeout"),
                null,
                null,
                null,
                null,
                "evt-2");
        RecalledItem newer = new RecalledItem(
                "evt-2",
                "sess-2",
                "decision",
                "codex",
                "client-2",
                project.toString(),
                Instant.parse("2026-10-01T12:00:00Z"),
                "Add IMMEDIATE transaction begin for SQLite writers",
                "Avoid SQLITE_BUSY upgrades after an initial read.",
                List.of("Retry loop in application code"),
                0.95,
                List.of(),
                null,
                null,
                null,
                "evt-1",
                null);

        MemoryRecallOperations recall = new MemoryRecallOperations() {
            @Override
            public RecallResult recall(String scope, int withinHours, List<String> kinds, Integer limit) {

                return new RecallResult(scope, withinHours, kinds, 2, List.of(newer, older), "lexical", false);
            }

            @Override
            public RecallResult recall(
                    String scope,
                    String projectScope,
                    String query,
                    int withinHours,
                    List<String> kinds,
                    Integer limit,
                    boolean includeSuperseded) {

                return new RecallResult(projectScope, withinHours, kinds, 2, List.of(newer, older), "lexical", false);
            }
        };

        AdrExportPlanner planner = new AdrExportPlanner(recall);

        var dryRun = planner.export(project, outputDir, 8760, true, false);
        assertThat(dryRun.mode()).isEqualTo("dry-run");
        assertThat(dryRun.applied()).isFalse();
        assertThat(dryRun.count()).isEqualTo(2);
        assertThat(Files.exists(outputDir)).isFalse();
        assertThat(dryRun.decisions().get(0).number()).isEqualTo(1);
        assertThat(dryRun.decisions().get(0).status()).isEqualTo("Superseded by evt-2");
        assertThat(dryRun.decisions().get(0).drift().referencedFiles()).containsExactly("src/main/Storage.java");
        assertThat(dryRun.decisions().get(1).number()).isEqualTo(2);
        assertThat(dryRun.decisions().get(1).status()).isEqualTo("Accepted");
        assertThat(dryRun.decisions().get(1).markdown()).contains("- **Supersedes:** `evt-1`");

        var applied = planner.export(project, outputDir, 8760, true, true);
        assertThat(applied.applied()).isTrue();
        assertThat(Files.exists(outputDir.resolve("DECISIONS.md"))).isTrue();
        assertThat(Files.exists(outputDir.resolve(applied.decisions().get(0).filename())))
                .isTrue();
        assertThat(Files.exists(outputDir.resolve(applied.decisions().get(1).filename())))
                .isTrue();
        assertThat(Files.readString(outputDir.resolve("DECISIONS.md")))
                .contains("ADR-0001", "ADR-0002", "Superseded by evt-2", "Accepted");
    }
}
