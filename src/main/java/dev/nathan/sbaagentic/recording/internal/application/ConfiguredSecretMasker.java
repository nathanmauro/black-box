package dev.nathan.sbaagentic.recording.internal.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the exact values of this service's own configured credentials wherever they appear in a
 * text, whatever is around them: the way a CI system masks its registered secrets. The display-shape
 * rules in {@link RedactionService} need to recognize a name next to a value; this needs only the
 * value, so a shape nobody anticipated still cannot carry one of these values into storage.
 *
 * <p>Each value is matched as written and in the encodings a display can put it in: JSON-escaped
 * (also HTML-safe, and escaped twice), URL-encoded, XML-escaped and Base64. Matching is exact and
 * case-sensitive. A value that is short, common or not a credential at all (a path, a plain URL, a
 * number) is not registered: see {@link #isStrong}.
 */
final class ConfiguredSecretMasker {

    static final int MIN_SECRET_CHARS = 8;

    private static final String REDACTED = "[REDACTED]";
    // A PEM block is longer, but the built-in rules already replace those by shape.
    private static final int MAX_SECRET_CHARS = 8192;
    private static final int MIN_DISTINCT_CHARS = 5;
    // Long enough that one character class is still not a dictionary word.
    private static final int LONG_SECRET_CHARS = 16;
    // Values that documentation, defaults and development setups use for credential-named settings.
    private static final Set<String> COMMON_VALUES = Set.of(
            "postgres",
            "postgresql",
            "password",
            "password1",
            "password123",
            "passw0rd",
            "changeme",
            "changeit",
            "lm-studio",
            "localhost",
            "username",
            "required",
            "disabled",
            "enabled",
            "undefined",
            "redacted",
            "[redacted]",
            "placeholder",
            "your-api-key",
            "your_api_key",
            "test-token",
            "testtoken",
            "development",
            "anonymous",
            "sk-no-key-required");
    private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://\\S*");
    private static final Pattern URL_PASSWORD = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^/@:\\s]*:([^/@\\s]+)@.*");
    private static final ConfiguredSecretMasker NONE = new ConfiguredSecretMasker(List.of(), 0);

    // Every form to replace, by first character, longest first so a value never loses to its prefix.
    private final Map<Character, List<String>> formsByFirstChar;
    private final int secretCount;

    private ConfiguredSecretMasker(Collection<String> forms, int secretCount) {
        Map<Character, List<String>> byFirstChar = new HashMap<>();
        for (String form : forms) {
            byFirstChar
                    .computeIfAbsent(form.charAt(0), first -> new ArrayList<>())
                    .add(form);
        }
        for (List<String> sameStart : byFirstChar.values()) {
            sameStart.sort(Comparator.comparingInt(String::length).reversed());
        }
        this.formsByFirstChar = byFirstChar;
        this.secretCount = secretCount;
    }

    static ConfiguredSecretMasker none() {

        return NONE;
    }

    /** A masker for the strong values among {@code candidates}; weak ones are left alone. */
    static ConfiguredSecretMasker ofValues(Collection<String> candidates) {
        Set<String> secrets = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String value = candidate.strip();
            Matcher url = URL_PASSWORD.matcher(value);
            if (url.matches()) {
                // jdbc:postgresql://user:password@host: the password is the credential.
                value = url.group(1);
            }
            if (isStrong(value)) {
                secrets.add(value);
            }
        }
        if (secrets.isEmpty()) {

            return NONE;
        }
        Set<String> forms = new LinkedHashSet<>();
        for (String secret : secrets) {
            for (String form : forms(secret)) {
                if (form.length() >= MIN_SECRET_CHARS) {
                    forms.add(form);
                }
            }
        }

        return new ConfiguredSecretMasker(forms, secrets.size());
    }

    /**
     * Whether a value is specific enough to replace everywhere it appears. Masking is not tied to a
     * name, so a weak value would be replaced across all captured text: a development password of
     * {@code postgres} must not turn every mention of PostgreSQL into a marker.
     */
    static boolean isStrong(String value) {
        if (value == null || value.length() < MIN_SECRET_CHARS || value.length() > MAX_SECRET_CHARS) {

            return false;
        }
        if (COMMON_VALUES.contains(value.toLowerCase(Locale.ROOT)) || value.contains("${")) {

            return false;
        }
        if (value.chars().distinct().count() < MIN_DISTINCT_CHARS
                || value.chars().allMatch(ConfiguredSecretMasker::isNumberChar)) {

            return false;
        }
        if (URL.matcher(value).matches() || isExistingPath(value)) {

            return false;
        }

        return value.length() >= LONG_SECRET_CHARS || characterClasses(value) >= 2;
    }

    private static boolean isNumberChar(int character) {

        return Character.isDigit(character) || character == '.' || character == '-' || character == ',';
    }

    private static int characterClasses(String value) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean other = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLowerCase(character)) {
                lower = true;
            } else if (Character.isUpperCase(character)) {
                upper = true;
            } else if (Character.isDigit(character)) {
                digit = true;
            } else {
                other = true;
            }
        }

        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
    }

    // A credential-named setting often holds where the credential is (SBA_AUTH_TOKEN_FILE). Base64
    // can start with a slash too, so a value is a path only when the file system agrees: it exists,
    // or it has several segments and its directory exists.
    private static boolean isExistingPath(String value) {
        boolean home = value.startsWith("~/");
        if (!home && !value.startsWith("/") && !value.startsWith("./") && !value.startsWith("../")) {

            return false;
        }
        try {
            Path path = home ? Path.of(System.getProperty("user.home", "/"), value.substring(2)) : Path.of(value);
            Path parent = path.toAbsolutePath().normalize().getParent();

            return Files.exists(path) || (parent != null && parent.getNameCount() > 0 && Files.isDirectory(parent));
        } catch (InvalidPathException | SecurityException ex) {

            return false;
        }
    }

    private static Set<String> forms(String secret) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(secret);

        String json = jsonEscape(secret, false);
        forms.add(json);
        forms.add(json.replace("/", "\\/"));
        // JSON carried as a string inside JSON, as a hook stores a tool response.
        forms.add(jsonEscape(json, false));
        String htmlSafe = jsonEscape(secret, true);
        forms.add(htmlSafe);
        forms.add(upperCaseUnicodeEscapes(htmlSafe));

        String url = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        forms.add(url);
        forms.add(url.replace("+", "%20"));
        forms.add(lowerCasePercentEscapes(url));
        forms.add(lowerCasePercentEscapes(url.replace("+", "%20")));

        String xml = secret.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        forms.add(xml);
        forms.add(xml.replace("\"", "&quot;").replace("'", "&apos;"));

        // A property list <data> value, or the value handed to a tool that shows Base64.
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        forms.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
        forms.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));

        return forms;
    }

    private static String jsonEscape(String text, boolean htmlSafe) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    boolean markup = htmlSafe && "<>&='".indexOf(character) >= 0;
                    if (character < 0x20 || markup || (htmlSafe && character > 0x7e)) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }

        return escaped.toString();
    }

    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u[0-9a-f]{4}");
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9A-F]{2}");

    private static String upperCaseUnicodeEscapes(String text) {

        return UNICODE_ESCAPE
                .matcher(text)
                .replaceAll(match -> Matcher.quoteReplacement(
                        "\\u" + match.group().substring(2).toUpperCase(Locale.ROOT)));
    }

    private static String lowerCasePercentEscapes(String text) {

        return PERCENT_ESCAPE.matcher(text).replaceAll(match -> match.group().toLowerCase(Locale.ROOT));
    }

    boolean isEmpty() {

        return secretCount == 0;
    }

    /** How many distinct configured values are masked. */
    int secretCount() {

        return secretCount;
    }

    /** The text with every form of every registered value replaced, or the same text when none is. */
    String mask(String text) {

        return maskWithin(text, Integer.MAX_VALUE);
    }

    /**
     * {@link #mask} for the values that start before {@code limit}: what a caller that reads only
     * that much of the text needs, so a value across its cut is replaced whole, not left as a prefix.
     */
    String maskWithin(String text, int limit) {
        if (text == null || secretCount == 0) {

            return text;
        }
        StringBuilder masked = null;
        int copied = 0;
        int end = Math.min(text.length(), limit);
        int index = 0;
        while (index < end) {
            String form = formAt(text, index);
            if (form == null) {
                index++;
                continue;
            }
            if (masked == null) {
                masked = new StringBuilder(text.length());
            }
            masked.append(text, copied, index).append(REDACTED);
            index += form.length();
            copied = index;
        }

        return masked == null
                ? text
                : masked.append(text, copied, text.length()).toString();
    }

    private String formAt(String text, int index) {
        List<String> candidates = formsByFirstChar.get(text.charAt(index));
        if (candidates == null) {

            return null;
        }
        for (String form : candidates) {
            if (text.startsWith(form, index)) {

                return form;
            }
        }

        return null;
    }
}
