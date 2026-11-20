package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exports recorded project {@code Decision} captures into Markdown Architecture Decision Records
 * ({@code docs/adr/NNNN-<slug>.md} and {@code DECISIONS.md}) with optional Git drift inspection.
 * Defaults to dry-run mode unless {@code --apply} is supplied.
 */
public final class AdrExportPlanner {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    private static final Pattern FILE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_./-])([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\\.[A-Za-z0-9]{1,10}|[A-Za-z0-9_-]+\\.(?:java|ts|tsx|js|mjs|py|sh|yml|yaml|json|md|sql|xml|css|html))(?![A-Za-z0-9_./-])");

    private final MemoryRecallOperations memoryRecall;

    public AdrExportPlanner(MemoryRecallOperations memoryRecall) {
        this.memoryRecall = memoryRecall;
    }

    public AdrExportReport export(
            Path projectPath, Path outputDir, int withinHours, boolean includeSuperseded, boolean apply)
            throws IOException {
        Path normalizedProject = projectPath.toAbsolutePath().normalize();
        Path normalizedOutput = outputDir.toAbsolutePath().normalize();
        int clampedHours = Math.max(1, Math.min(withinHours, 8760));

        RecallResult recalled = memoryRecall.recall(
                null, normalizedProject.toString(), null, clampedHours, List.of("decision"), 50, includeSuperseded);

        List<RecalledItem> ordered =
                new ArrayList<>(recalled != null && recalled.items() != null ? recalled.items() : List.of());
        ordered.sort(Comparator.comparing(
                        (RecalledItem item) -> item.observedAt() == null ? Instant.EPOCH : item.observedAt())
                .thenComparing(item -> item.eventId() == null ? "" : item.eventId()));

        boolean gitRepo = isGitRepository(normalizedProject);
        List<AdrDocument> documents = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            RecalledItem item = ordered.get(i);
            int number = i + 1;
            String title = firstNonBlank(item.headline(), "Recorded decision " + number);
            String filename = String.format(Locale.ROOT, "%04d-%s.md", number, slug(title));
            String status = item.supersededByEventId() != null
                            && !item.supersededByEventId().isBlank()
                    ? "Superseded by " + item.supersededByEventId()
                    : "Accepted";
            String date = item.observedAt() != null ? ISO_DATE.format(item.observedAt()) : "unknown";
            DriftSummary drift = inspectDrift(normalizedProject, gitRepo, item);
            String markdown = renderAdrMarkdown(number, title, status, date, item, drift);
            documents.add(new AdrDocument(
                    number,
                    filename,
                    normalizedOutput.resolve(filename).toString(),
                    title,
                    status,
                    date,
                    item.eventId(),
                    item.sessionId(),
                    item.source(),
                    item.supersedesEventId(),
                    item.supersededByEventId(),
                    drift,
                    markdown));
        }

        String indexMarkdown = renderIndexMarkdown(normalizedProject.toString(), documents);
        if (apply) {
            writeOutput(normalizedOutput, documents, indexMarkdown);
        }

        return new AdrExportReport(
                apply ? "applied" : "dry-run",
                apply,
                normalizedProject.toString(),
                normalizedOutput.toString(),
                clampedHours,
                includeSuperseded,
                documents.size(),
                documents,
                indexMarkdown);
    }

    private static void writeOutput(Path outputDir, List<AdrDocument> documents, String indexMarkdown)
            throws IOException {
        if (Files.isSymbolicLink(outputDir)) {
            throw new IOException("Refusing to write ADRs into symbolic-link directory: " + outputDir);
        }
        Files.createDirectories(outputDir);
        Path indexFile = outputDir.resolve("DECISIONS.md");
        writeRegularFile(indexFile, indexMarkdown);
        for (AdrDocument doc : documents) {
            Path target = outputDir.resolve(doc.filename()).normalize();
            if (!target.startsWith(outputDir) || target.equals(outputDir)) {
                throw new IOException("Refusing to write ADR outside output directory: " + target);
            }
            writeRegularFile(target, doc.markdown());
        }
    }

    private static void writeRegularFile(Path file, String content) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Refusing to overwrite symbolic link: " + file);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static DriftSummary inspectDrift(Path projectRoot, boolean gitRepo, RecalledItem item) {
        List<String> referenced = extractReferencedFiles(projectRoot, item);
        if (!gitRepo || item.observedAt() == null) {

            return new DriftSummary(false, null, referenced, List.of(), false);
        }
        String sinceIso = item.observedAt().toString();
        Integer commits = runGitCount(projectRoot, sinceIso);
        if (commits == null) {

            return new DriftSummary(false, null, referenced, List.of(), false);
        }
        List<String> modified =
                referenced.isEmpty() ? List.of() : runGitModifiedFiles(projectRoot, sinceIso, referenced);

        return new DriftSummary(true, commits, referenced, modified, !modified.isEmpty());
    }

    static List<String> extractReferencedFiles(Path projectRoot, RecalledItem item) {
        StringBuilder combined = new StringBuilder();
        appendText(combined, item.headline());
        appendText(combined, item.rationale());
        if (item.alternatives() != null) {
            item.alternatives().forEach(alt -> appendText(combined, alt));
        }
        if (item.openLoops() != null) {
            item.openLoops().forEach(loop -> appendText(combined, loop));
        }
        Set<String> candidates = new LinkedHashSet<>();
        Matcher matcher = FILE_TOKEN.matcher(combined.toString());
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.startsWith("/") || token.contains("..")) {
                continue;
            }
            Path resolved = projectRoot.resolve(token).normalize();
            if (resolved.startsWith(projectRoot)
                    && !resolved.equals(projectRoot)
                    && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                candidates.add(projectRoot.relativize(resolved).toString().replace('\\', '/'));
            }
        }

        return List.copyOf(candidates);
    }

    private static void appendText(StringBuilder builder, String text) {
        if (text != null && !text.isBlank()) {
            builder.append(text).append('\n');
        }
    }

    private static boolean isGitRepository(Path projectRoot) {

        return Files.exists(projectRoot.resolve(".git"));
    }

    private static Integer runGitCount(Path projectRoot, String sinceIso) {
        List<String> command =
                List.of("git", "-C", projectRoot.toString(), "rev-list", "--count", "--since=" + sinceIso, "HEAD");
        String out = runGit(command);
        if (out == null) {

            return null;
        }
        try {

            return Integer.parseInt(out.strip());
        } catch (NumberFormatException ex) {

            return null;
        }
    }

    private static List<String> runGitModifiedFiles(Path projectRoot, String sinceIso, List<String> referencedFiles) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(projectRoot.toString());
        command.add("log");
        command.add("--since=" + sinceIso);
        command.add("--name-only");
        command.add("--pretty=format:");
        command.add("--");
        command.addAll(referencedFiles);
        String out = runGit(command);
        if (out == null || out.isBlank()) {

            return List.of();
        }
        Set<String> modified = new LinkedHashSet<>();
        for (String line : out.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && referencedFiles.contains(trimmed)) {
                modified.add(trimmed);
            }
        }

        return List.copyOf(modified);
    }

    private static String runGit(List<String> argv) {
        try {
            Process process =
                    new ProcessBuilder(argv).redirectErrorStream(false).start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();

                return null;
            }
            if (process.exitValue() != 0) {

                return null;
            }

            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {

            return null;
        }
    }

    private static String renderAdrMarkdown(
            int number, String title, String status, String date, RecalledItem item, DriftSummary drift) {
        StringBuilder md = new StringBuilder();
        md.append("# ADR-")
                .append(String.format(Locale.ROOT, "%04d", number))
                .append(": ")
                .append(title)
                .append("\n\n");
        md.append("- **Status:** ").append(status).append("\n");
        md.append("- **Date:** ").append(date);
        if (item.observedAt() != null) {
            md.append(" (`").append(item.observedAt()).append("`)");
        }
        md.append("\n");
        md.append("- **Source:** `")
                .append(firstNonBlank(item.source(), "unknown"))
                .append("`");
        if (item.sessionId() != null) {
            md.append(" · session `").append(item.sessionId()).append("`");
        }
        if (item.eventId() != null) {
            md.append(" · event `").append(item.eventId()).append("`");
        }
        md.append("\n");
        if (item.supersedesEventId() != null && !item.supersedesEventId().isBlank()) {
            md.append("- **Supersedes:** `").append(item.supersedesEventId()).append("`\n");
        }
        if (item.confidence() != null && Double.isFinite(item.confidence())) {
            md.append("- **Confidence:** ")
                    .append(Math.round(item.confidence() * 100))
                    .append("%\n");
        }
        if (drift.gitAvailable() && drift.commitsSinceCapture() != null) {
            md.append("- **Git Drift:** ").append(drift.commitsSinceCapture()).append(" commit(s) since capture");
            if (!drift.modifiedReferencedFiles().isEmpty()) {
                md.append("; referenced files modified since capture: ")
                        .append(drift.modifiedReferencedFiles().stream()
                                .map(f -> "`" + f + "`")
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""));
            }
            md.append("\n");
        }

        md.append("\n## Decision\n\n").append(title).append("\n");

        if (item.rationale() != null && !item.rationale().isBlank()) {
            md.append("\n## Context and Rationale\n\n")
                    .append(item.rationale().strip())
                    .append("\n");
        }
        if (item.alternatives() != null && !item.alternatives().isEmpty()) {
            md.append("\n## Rejected Alternatives\n\n");
            for (String alt : item.alternatives()) {
                if (alt != null && !alt.isBlank()) {
                    md.append("- ").append(alt.strip()).append("\n");
                }
            }
        }
        if (item.openLoops() != null && !item.openLoops().isEmpty()) {
            md.append("\n## Open Loops\n\n");
            for (String loop : item.openLoops()) {
                if (loop != null && !loop.isBlank()) {
                    md.append("- ").append(loop.strip()).append("\n");
                }
            }
        }

        return md.toString();
    }

    private static String renderIndexMarkdown(String project, List<AdrDocument> documents) {
        StringBuilder md = new StringBuilder();
        md.append("# Architecture Decision Records\n\n");
        md.append("Exported from Black Box for `").append(project).append("`.\n\n");
        if (documents.isEmpty()) {
            md.append("_No recorded decisions found in the selected window._\n");

            return md.toString();
        }
        md.append("| ADR | Decision | Status | Date | Source | Drift |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        for (AdrDocument doc : documents) {
            String driftCell = !doc.drift().gitAvailable()
                    ? "n/a"
                    : doc.drift().drifted()
                            ? doc.drift().modifiedReferencedFiles().size() + " file(s) changed"
                            : doc.drift().commitsSinceCapture() + " commit(s), 0 file changes";
            md.append("| [ADR-")
                    .append(String.format(Locale.ROOT, "%04d", doc.number()))
                    .append("](")
                    .append(doc.filename())
                    .append(") | ")
                    .append(escapeTable(doc.title()))
                    .append(" | ")
                    .append(escapeTable(doc.status()))
                    .append(" | ")
                    .append(doc.date())
                    .append(" | `")
                    .append(escapeTable(firstNonBlank(doc.source(), "unknown")))
                    .append("` | ")
                    .append(escapeTable(driftCell))
                    .append(" |\n");
        }

        return md.toString();
    }

    private static String escapeTable(String text) {

        return text == null ? "" : text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String slug(String value) {
        String slug = String.valueOf(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {

            return "decision";
        }

        return slug.length() > 64 ? slug.substring(0, 64).replaceAll("-$", "") : slug;
    }

    private static String firstNonBlank(String value, String fallback) {

        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public record AdrExportReport(
            String mode,
            boolean applied,
            String project,
            String outputDir,
            int withinHours,
            boolean includeSuperseded,
            int count,
            List<AdrDocument> decisions,
            String indexMarkdown) {}

    public record AdrDocument(
            int number,
            String filename,
            String path,
            String title,
            String status,
            String date,
            String eventId,
            String sessionId,
            String source,
            String supersedesEventId,
            String supersededByEventId,
            DriftSummary drift,
            String markdown) {}

    public record DriftSummary(
            boolean gitAvailable,
            Integer commitsSinceCapture,
            List<String> referencedFiles,
            List<String> modifiedReferencedFiles,
            boolean drifted) {}
}
