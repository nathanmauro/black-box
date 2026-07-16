package dev.nathan.sbaagentic.runner.gate;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import org.springframework.stereotype.Component;

@Component
public class StoryFrontmatterParser {

    private static final Pattern DELIMITER = Pattern.compile("(?m)^[\\t ]*---[\\t ]*\\r?$");

    public Optional<ParsedStory> parse(String specBody) {
        if (specBody == null) {
            return Optional.empty();
        }

        Matcher delimiter = DELIMITER.matcher(specBody);
        if (!delimiter.find() || delimiter.start() != 0) {
            return Optional.empty();
        }
        int yamlStart = skipLineBreak(specBody, delimiter.end());
        if (!delimiter.find()) {
            return Optional.empty();
        }

        String yamlText = specBody.substring(yamlStart, delimiter.start());
        int bodyStart = skipLineBreak(specBody, delimiter.end());
        try {
            Object loaded = new Yaml().load(yamlText);
            Map<?, ?> values;
            if (loaded == null) {
                values = Map.of();
            }
            else if (loaded instanceof Map<?, ?> map) {
                values = map;
            }
            else {
                return Optional.empty();
            }

            StoryFrontmatter frontmatter = new StoryFrontmatter(
                    stringValue(values.get("story")),
                    stringValue(values.get("repo")),
                    stringValue(values.get("mode")),
                    stringValue(values.get("verify")),
                    booleanValue(values.get("push")),
                    integerValue(values.get("priority")));
            return Optional.of(new ParsedStory(
                    frontmatter,
                    trimLeadingBlankLines(specBody.substring(bodyStart))));
        }
        catch (YAMLException | ClassCastException ex) {
            return Optional.empty();
        }
    }

    private static int skipLineBreak(String value, int offset) {
        if (offset < value.length() && value.charAt(offset) == '\r') {
            offset++;
        }
        if (offset < value.length() && value.charAt(offset) == '\n') {
            offset++;
        }
        return offset;
    }

    private static String trimLeadingBlankLines(String value) {
        int offset = 0;
        while (offset < value.length()) {
            int lineEnd = value.indexOf('\n', offset);
            int contentEnd = lineEnd < 0 ? value.length() : lineEnd;
            if (contentEnd > offset && value.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            if (!value.substring(offset, contentEnd).isBlank()) {
                break;
            }
            if (lineEnd < 0) {
                return "";
            }
            offset = lineEnd + 1;
        }
        return value.substring(offset);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            if ("true".equalsIgnoreCase(stringValue.strip())) {
                return true;
            }
            if ("false".equalsIgnoreCase(stringValue.strip())) {
                return false;
            }
        }
        return null;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.valueOf(stringValue.strip());
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    public record ParsedStory(StoryFrontmatter frontmatter, String bodyMarkdown) {
    }
}
