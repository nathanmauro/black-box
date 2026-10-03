package dev.nathan.sbaagentic.summary.internal.application;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.samskivert.mustache.Mustache;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.summary.ExportTarget;
import dev.nathan.sbaagentic.summary.SummaryExport;
import dev.nathan.sbaagentic.summary.SummaryExportOperations;
import dev.nathan.sbaagentic.summary.SummaryExportProperties;
import dev.nathan.sbaagentic.summary.SummaryExportProperties.Target;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SummaryExportService implements SummaryExportOperations {

    private static final String MARKDOWN_FILE = "markdown-file";
    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final RecordingCatalog repository;
    private final SummaryExportProperties properties;
    private final ResourceLoader resourceLoader;

    public SummaryExportService(
            RecordingCatalog repository, SummaryExportProperties properties, ResourceLoader resourceLoader) {
        this.repository = repository;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    public List<ExportTarget> targets() {

        return properties.getTargets().stream()
                .filter(Target::isEnabled)
                .map(target -> new ExportTarget(
                        target.getId(),
                        firstNonBlank(target.getLabel(), target.getId()),
                        firstNonBlank(target.getType(), MARKDOWN_FILE)))
                .toList();
    }

    public SummaryExport exportSummary(String sessionId, String targetId) {
        AgentSession session = repository
                .findSessionById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Session not found"));
        if (session.summary() == null || session.summary().isBlank()) {
            throw new ResponseStatusException(CONFLICT, "Session has no summary to export");
        }

        Target target = findTarget(targetId);
        if (!MARKDOWN_FILE.equalsIgnoreCase(firstNonBlank(target.getType(), MARKDOWN_FILE))) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported export target type: " + target.getType());
        }

        return writeMarkdownFile(session, target);
    }

    private Target findTarget(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Export target id is required");
        }

        return properties.getTargets().stream()
                .filter(Target::isEnabled)
                .filter(target -> targetId.equals(target.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Export target not found"));
    }

    private SummaryExport writeMarkdownFile(AgentSession session, Target target) {
        Path exportRoot = exportRoot(target);
        Map<String, Object> model = templateModel(session, target);
        Path notePath = exportRoot
                .resolve(render(firstNonBlank(target.getSubdirectoryTemplate(), ""), model))
                .resolve(render(firstNonBlank(target.getFilenameTemplate(), "{{date}}-{{slug}}-{{shortId}}.md"), model))
                .normalize();
        if (!notePath.startsWith(exportRoot) || notePath.equals(exportRoot)) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Unable to resolve export path");
        }

        try {
            // Render before creating directories or staging data, so template failures change nothing.
            String markdown = render(loadTemplate(target), model);
            Path relative = exportRoot.relativize(notePath);
            // A configured root alias is intentional. Descendant links are not export destinations.
            exportRoot = Files.createDirectories(exportRoot).toRealPath();
            notePath = exportRoot.resolve(relative);
            writeAtomically(exportRoot, notePath, markdown);
        } catch (IOException ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Unable to export summary", ex);
        }

        return new SummaryExport(
                session.id(),
                target.getId(),
                firstNonBlank(target.getLabel(), target.getId()),
                firstNonBlank(target.getType(), MARKDOWN_FILE),
                notePath.toString(),
                exportRoot.relativize(notePath).toString());
    }

    private record DirectoryIdentity(Path path, Object key) {}

    private void writeAtomically(Path root, Path note, String markdown) throws IOException {
        // Portable NIO has no descriptor-relative mkdir/rename on every supported provider (macOS
        // does not expose SecureDirectoryStream). Require caller-controlled, stable directories;
        // these checks reject existing links and detected swaps, not adversarial rename races.
        List<DirectoryIdentity> parents = new ArrayList<>();
        parents.add(directoryIdentity(root));
        Path parent = root;
        for (Path component : root.relativize(note.getParent())) {
            if (component.toString().isEmpty()) continue;
            verifyDirectories(parents);
            parent = parent.resolve(component);
            try {
                Files.createDirectory(parent);
            } catch (FileAlreadyExistsException ignored) {
                // Read without following links below, including dangling links.
            }
            parents.add(directoryIdentity(parent));
        }
        verifyDirectories(parents);
        Object previous = regularFileKey(note);
        Path staged = null;
        Object stagedKey = null;
        try {
            staged = Files.createTempFile(parent, ".blackbox-export-", ".tmp");
            stagedKey = regularFileKey(staged);
            writeStaged(staged, markdown);
            verifyDirectories(parents);
            verifyFiles(note, previous, staged, stagedKey);
            // Preserve existing POSIX permissions while avoiding an in-place write to hard links.
            if (previous != null
                    && Files.getFileAttributeView(note, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            != null) {
                Files.getFileAttributeView(staged, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .setPermissions(Files.getPosixFilePermissions(note, LinkOption.NOFOLLOW_LINKS));
            }
            verifyDirectories(parents);
            verifyFiles(note, previous, staged, stagedKey);
            publish(staged, note);
        } finally {
            if (staged != null) {
                try {
                    // A displaced directory can retain its staged file. Never follow its replacement
                    // path for cleanup, or delete a different file that appeared at the staged name.
                    verifyDirectories(parents);
                    if (Objects.equals(stagedKey, regularFileKey(staged))) Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Do not mask the original failure or traverse changed directories for cleanup.
                }
            }
        }
    }

    void writeStaged(Path staged, String markdown) throws IOException {
        try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(markdown);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    void publish(Path staged, Path note) throws IOException {
        // No non-atomic fallback: an unsupported move must leave the previous note untouched.
        Files.move(staged, note, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static DirectoryIdentity directoryIdentity(Path path) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.fileKey() == null) {
            throw new IOException("Export directory is linked, invalid or lacks a stable identity");
        }

        return new DirectoryIdentity(path, attributes.fileKey());
    }

    private static void verifyDirectories(List<DirectoryIdentity> directories) throws IOException {
        for (DirectoryIdentity directory : directories) {
            if (!directory.key().equals(directoryIdentity(directory.path()).key())) {
                throw new IOException("Export directory identity changed");
            }
        }
    }

    private static void verifyFiles(Path note, Object previous, Path staged, Object stagedKey) throws IOException {
        if (!Objects.equals(previous, regularFileKey(note)) || !Objects.equals(stagedKey, regularFileKey(staged))) {
            throw new IOException("Export file identity changed");
        }
    }

    private static Object regularFileKey(Path file) throws IOException {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.fileKey() == null) {
                throw new IOException("Export destination is linked, invalid or lacks a stable identity");
            }

            return attributes.fileKey();
        } catch (NoSuchFileException missing) {

            return null;
        }
    }

    private Path exportRoot(Target target) {
        String configured = target.getDirectory();
        if (configured == null || configured.isBlank()) {
            throw new ResponseStatusException(
                    BAD_REQUEST, "Export directory is not configured for target: " + target.getId());
        }
        if (configured.equals("~")) {

            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (configured.startsWith("~/")) {

            return Path.of(System.getProperty("user.home"), configured.substring(2))
                    .toAbsolutePath()
                    .normalize();
        }

        return Path.of(configured).toAbsolutePath().normalize();
    }

    private String loadTemplate(Target target) throws IOException {
        String location = firstNonBlank(target.getTemplate(), "classpath:/exports/summary-markdown.mustache");
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Export template not found: " + location);
        }
        try (InputStream input = resource.getInputStream()) {

            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> templateModel(AgentSession session, Target target) {
        Map<String, Object> model = new LinkedHashMap<>();
        String cwd = session.cwd();
        model.put("targetId", target.getId());
        model.put("targetLabel", firstNonBlank(target.getLabel(), target.getId()));
        model.put("sessionId", session.id());
        model.put("sessionIdYaml", yaml(session.id()));
        model.put("clientSessionId", session.clientSessionId());
        model.put("clientSessionIdYaml", yaml(session.clientSessionId()));
        model.put("source", session.source());
        model.put("sourceYaml", yaml(session.source()));
        model.put("sourceTag", tag(session.source()));
        model.put("title", session.title());
        model.put("titleYaml", yaml(session.title()));
        model.put("cwd", cwd);
        model.put("cwdYaml", yaml(cwd));
        model.put("cwdTable", escapeTable(cwd));
        model.put("hasCwd", cwd != null && !cwd.isBlank());
        model.put("startedAt", session.startedAt().toString());
        model.put("startedAtYaml", yaml(session.startedAt().toString()));
        model.put("lastSeenAt", session.lastSeenAt().toString());
        model.put("lastSeenAtYaml", yaml(session.lastSeenAt().toString()));
        model.put("eventCount", session.eventCount());
        model.put("summary", session.summary().strip());
        model.put("date", DAY.format(session.startedAt()));
        model.put("month", MONTH.format(session.startedAt()));
        model.put("slug", slug(session.title()));
        model.put("shortId", shortId(session.id()));

        return model;
    }

    private static String render(String template, Map<String, Object> model) {

        return Mustache.compiler()
                .escapeHTML(false)
                .compile(template)
                .execute(model)
                .strip();
    }

    private static String yaml(String value) {

        return "\""
                + String.valueOf(value)
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                + "\"";
    }

    private static String escapeTable(String value) {

        return value == null ? "" : value.replace("|", "\\|");
    }

    private static String slug(String value) {
        String slug = String.valueOf(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {

            return "session-summary";
        }

        return slug.length() > 64 ? slug.substring(0, 64).replaceAll("-$", "") : slug;
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 8) {

            return String.valueOf(id);
        }

        return id.substring(0, 8);
    }

    private static String tag(String value) {
        String tag = String.valueOf(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");

        return tag.isBlank() ? "unknown" : tag;
    }

    private static String firstNonBlank(String value, String fallback) {

        return value == null || value.isBlank() ? fallback : value;
    }
}
