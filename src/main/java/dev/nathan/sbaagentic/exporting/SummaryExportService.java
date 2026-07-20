package dev.nathan.sbaagentic.exporting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.samskivert.mustache.Mustache;

import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.AgentSession;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class SummaryExportService {

    private static final String MARKDOWN_FILE = "markdown-file";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final RecordingCatalog repository;
    private final SbaProperties properties;
    private final ResourceLoader resourceLoader;

    public SummaryExportService(RecordingCatalog repository, SbaProperties properties, ResourceLoader resourceLoader) {
        this.repository = repository;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    public List<ExportTarget> targets() {
        return properties.getExports().getTargets().stream()
                .filter(SbaProperties.Target::isEnabled)
                .map(target -> new ExportTarget(
                        target.getId(),
                        firstNonBlank(target.getLabel(), target.getId()),
                        firstNonBlank(target.getType(), MARKDOWN_FILE)))
                .toList();
    }

    public SummaryExport exportSummary(String sessionId, String targetId) {
        AgentSession session = repository.findSessionById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Session not found"));
        if (session.summary() == null || session.summary().isBlank()) {
            throw new ResponseStatusException(CONFLICT, "Session has no summary to export");
        }

        SbaProperties.Target target = findTarget(targetId);
        if (!MARKDOWN_FILE.equalsIgnoreCase(firstNonBlank(target.getType(), MARKDOWN_FILE))) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported export target type: " + target.getType());
        }
        return writeMarkdownFile(session, target);
    }

    private SbaProperties.Target findTarget(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Export target id is required");
        }
        return properties.getExports().getTargets().stream()
                .filter(SbaProperties.Target::isEnabled)
                .filter(target -> targetId.equals(target.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Export target not found"));
    }

    private SummaryExport writeMarkdownFile(AgentSession session, SbaProperties.Target target) {
        Path exportRoot = exportRoot(target);
        Map<String, Object> model = templateModel(session, target);
        Path notePath = exportRoot
                .resolve(render(firstNonBlank(target.getSubdirectoryTemplate(), ""), model))
                .resolve(render(firstNonBlank(target.getFilenameTemplate(), "{{date}}-{{slug}}-{{shortId}}.md"), model))
                .normalize();
        if (!notePath.startsWith(exportRoot.normalize())) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Unable to resolve export path");
        }

        try {
            Files.createDirectories(notePath.getParent());
            Files.writeString(notePath, render(loadTemplate(target), model), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
        catch (IOException ex) {
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

    private Path exportRoot(SbaProperties.Target target) {
        String configured = target.getDirectory();
        if (configured == null || configured.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Export directory is not configured for target: " + target.getId());
        }
        if (configured.equals("~")) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (configured.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), configured.substring(2)).toAbsolutePath().normalize();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private String loadTemplate(SbaProperties.Target target) throws IOException {
        String location = firstNonBlank(target.getTemplate(), "classpath:/exports/summary-markdown.mustache");
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Export template not found: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> templateModel(AgentSession session, SbaProperties.Target target) {
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
        return "\"" + String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private static String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private static String slug(String value) {
        String slug = String.valueOf(value).toLowerCase(Locale.ROOT)
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

    public record ExportTarget(String id, String label, String type) {
    }

    public record SummaryExport(
            String sessionId,
            String targetId,
            String targetLabel,
            String targetType,
            String path,
            String relativePath) {
    }
}
