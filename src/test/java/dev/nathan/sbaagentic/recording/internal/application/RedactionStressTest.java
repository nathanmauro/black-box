package dev.nathan.sbaagentic.recording.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.nathan.sbaagentic.recording.IngestionProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Throughput and edge-case stress for the redaction entry points, extending the NAT-388
 * configured-secret masking. Synthetic values only; none has a real provider's shape.
 *
 * <p>Timing gates are deliberately loose (an order of magnitude under the measured rate) so they
 * catch catastrophic backtracking or an accidental quadratic pass, not machine noise.
 */
@DisplayName("Redaction stays fast, thread-safe, and leak-free under adversarial input")
class RedactionStressTest {

    private static final String SECRET = "Qm7Rv2Lx9Tb4Kp8Wz3Nd6Hs";
    private static final String MARK = "[REDACTED]";
    private static final int SCAN_BUDGET = 50_000;
    private static final Duration ADVERSARIAL_BUDGET = Duration.ofSeconds(3);

    private final RedactionService redactor =
            new RedactionService(new IngestionProperties(), ConfiguredSecretMasker.ofValues(List.of(SECRET)));

    private List<UnaryOperator<String>> entryPoints() {

        return List.of(
                redactor::redact,
                redactor::redactForExport,
                redactor::redactForSummary,
                redactor::redactJsonForSummary);
    }

    // ---------------------------------------------------------------- Base64 alignment (NAT-388)

    /** Every alignment a value can land on inside a longer Base64 stream, with bytes on both sides. */
    static Stream<Arguments> base64Displays() {
        List<Arguments> displays = new ArrayList<>();
        for (String before : List.of("", "u", "us", "user:", "registry-user:")) {
            for (String after : List.of("", "\n", "\r\n", ":", "|trailing")) {
                String plain = before + SECRET + after;
                byte[] bytes = plain.getBytes(StandardCharsets.UTF_8);
                displays.add(Arguments.of(
                        "std [" + before.length() + "," + after.length() + "]",
                        Base64.getEncoder().encodeToString(bytes)));
                displays.add(Arguments.of(
                        "url [" + before.length() + "," + after.length() + "]",
                        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
            }
        }

        return displays.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("base64Displays")
    void base64AtAnyAlignmentNoLongerCarriesTheValue(String alignment, String encoded) {
        String display = "{\"auths\":{\"registry.example\":{\"auth\":\"" + encoded + "\"}}}";

        for (UnaryOperator<String> entryPoint : entryPoints()) {
            String result = entryPoint.apply(display);

            assertThat(result).contains(MARK);
            assertThat(decodableSecret(result)).as("decoded %s", alignment).isFalse();
        }
    }

    @Test
    void echoPipedToBase64IsMasked() {
        // `echo $TOKEN | base64` appends a newline, so the last block differs from the bare value's.
        String display = "$ echo $SBA_SETTING | base64\n"
                + Base64.getEncoder().encodeToString((SECRET + "\n").getBytes(StandardCharsets.UTF_8));

        assertThat(decodableSecret(redactor.redact(display))).isFalse();
    }

    @Test
    void base64OfUnrelatedTextIsLeftAlone() {
        String encoded = Base64.getEncoder()
                .encodeToString("user:not-the-configured-value-at-all\n".getBytes(StandardCharsets.UTF_8));

        assertThat(redactor.redact("auth " + encoded)).isEqualTo("auth " + encoded);
    }

    // ---------------------------------------------------------------- Character-level edges

    @Test
    void valuesNextToSurrogatePairsCombiningMarksAndControlsAreMasked() {
        String text = "🔑" + SECRET + "é́" + SECRET + "\u0000" + SECRET + "​" + SECRET + "🔒";

        for (UnaryOperator<String> entryPoint : entryPoints()) {
            String result = entryPoint.apply(text);

            assertThat(result).doesNotContain(SECRET).startsWith("🔑" + MARK).endsWith(MARK + "🔒");
            assertThat(result.chars().filter(unit -> Character.isSurrogate((char) unit)).count()).isEqualTo(4);
        }
    }

    @Test
    void backToBackRepeatsAreEachMasked() {
        assertThat(redactor.redact(SECRET.repeat(5))).isEqualTo(MARK.repeat(5));
        assertThat(redactor.redact(SECRET + SECRET.substring(0, 6))).isEqualTo(MARK + SECRET.substring(0, 6));
    }

    @Test
    void deeplyNestedJsonNeitherOverflowsNorLeaks() {
        String nested = "[".repeat(5_000) + "\"" + SECRET + "\"" + "]".repeat(5_000);

        String summary = assertTimeoutPreemptively(ADVERSARIAL_BUDGET, () -> redactor.redactJsonForSummary(nested));

        assertThat(summary).doesNotContain(SECRET);
    }

    @Test
    void deeplyNestedStructuredCaptureNeitherOverflowsNorLeaks() {
        Object tree = SECRET;
        for (int depth = 0; depth < 2_000; depth++) {
            tree = depth % 2 == 0 ? List.of(tree) : Map.of("level", tree);
        }
        Object input = tree;

        Object redacted = assertTimeoutPreemptively(ADVERSARIAL_BUDGET, () -> redactor.redactDeep(input));

        // Past the depth cap the subtree is replaced, not walked, so stack use stays bounded.
        assertThat(String.valueOf(redacted))
                .doesNotContain(SECRET)
                .contains("nested deeper than 256 levels");
    }

    // ---------------------------------------------------------------- Adversarial regex inputs

    /** Near-miss inputs aimed at each rule family's quantifiers, all at the full scan budget. */
    static Stream<Arguments> adversarialInputs() {

        return Stream.of(
                Arguments.of("assignment prefixes", fill("password=")),
                Arguments.of("assignment without value", fill("api_key ") + "="),
                Arguments.of("escape runs", fill("\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"")),
                Arguments.of("escaped quote soup", fill("\\\"token\\\":\\\"")),
                Arguments.of("unterminated plist keys", fill("<key>SBA_TOKEN</key><!-- c -->")),
                Arguments.of("plist gaps", "<key>SBA_TOKEN</key>" + fill(" ")),
                Arguments.of("flag ladders", fill("--token --secret ")),
                Arguments.of("security command", fill("security add-generic-password -w ")),
                Arguments.of("hex api key segments", "apikey_" + fill("ab12_")),
                Arguments.of("numbered lines", fill("   12\t")),
                Arguments.of("unterminated private key", "-----BEGIN PRIVATE KEY-----\n" + fill("MIIEv")),
                Arguments.of("bearer ladders", fill("Authorization: Bearer ")),
                Arguments.of("url credential ladders", fill("https://user:pa@")),
                Arguments.of("configured prefix ladder", fill(SECRET.substring(0, SECRET.length() - 1))),
                Arguments.of("alternating quotes", fill("'\"")),
                Arguments.of("long single token", "x".repeat(SCAN_BUDGET)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adversarialInputs")
    void adversarialInputFinishesQuicklyOnEveryEntryPoint(String family, String input) {
        for (UnaryOperator<String> entryPoint : entryPoints()) {
            String result = assertTimeoutPreemptively(ADVERSARIAL_BUDGET, () -> entryPoint.apply(input), family);

            assertThat(result).isNotNull();
        }
    }

    @Test
    void oversizedInputIsClippedNotScannedInFull() {
        String huge = "noise ".repeat(1_000_000) + SECRET;

        String result = assertTimeoutPreemptively(ADVERSARIAL_BUDGET, () -> redactor.redact(huge));

        assertThat(result).endsWith("…[truncated]").doesNotContain(SECRET);
        assertThat(result.length()).isLessThan(SCAN_BUDGET + 100);
    }

    // ---------------------------------------------------------------- Throughput and concurrency

    @Test
    void mixedCorpusThroughputStaysAboveFloor() {
        List<String> corpus = corpus(new Random(388), 2_000);
        long chars = corpus.stream().mapToLong(String::length).sum();
        corpus.forEach(redactor::redact);

        long started = System.nanoTime();
        for (String text : corpus) {
            redactor.redact(text);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        double mbPerSecond = chars / 1_000_000.0 / seconds;
        System.out.printf(
                "redaction throughput: %.1f MB/s over %d texts (%.1f MB)%n", mbPerSecond, corpus.size(), chars / 1e6);

        // Measured at tens of MB/s on a laptop; one MB/s means something went quadratic.
        assertThat(mbPerSecond).isGreaterThan(1.0);
    }

    @Test
    void concurrentRedactionMatchesSequentialAndNeverLeaks() throws Exception {
        List<String> corpus = corpus(new Random(1984), 400);
        List<String> expected = corpus.stream().map(redactor::redact).toList();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<String>>> runs = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                runs.add(pool.submit(() -> corpus.stream().map(redactor::redact).toList()));
            }
            for (Future<List<String>> run : runs) {
                assertThat(run.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(expected).noneMatch(text -> text.contains(SECRET));
    }

    @Test
    void redactionIsIdempotentAcrossEntryPoints() {
        for (String text : corpus(new Random(7), 300)) {
            for (UnaryOperator<String> entryPoint : entryPoints()) {
                String once = entryPoint.apply(text);

                assertThat(entryPoint.apply(once)).isEqualTo(once);
                assertThat(once).doesNotContain(SECRET);
            }
        }
    }

    // ---------------------------------------------------------------- Helpers

    private static String fill(String unit) {
        StringBuilder text = new StringBuilder(SCAN_BUDGET);
        while (text.length() + unit.length() <= SCAN_BUDGET) {
            text.append(unit);
        }

        return text.toString();
    }

    /** Tool output, prose, JSON and config shapes, some holding the value, seeded and reproducible. */
    private static List<String> corpus(Random random, int size) {
        String[] clean = {
            "Compiled 412 sources in 3.2s; 0 warnings.",
            "{\"tool\":\"bash\",\"exit\":0,\"stdout\":\"ok\\n\",\"duration_ms\":18}",
            "diff --git a/src/App.java b/src/App.java\n@@ -1,3 +1,4 @@\n+import java.util.List;",
            "The recall endpoint now pages with a cursor instead of an offset.",
            "<key>Label</key>\n<string>com.example.agent</string>",
        };
        String[] secretShapes = {
            "export SBA_SETTING=" + SECRET,
            "{\"headers\":{\"x-custom\":\"" + SECRET + "\"}}",
            "| judge | " + SECRET + " | launchd |",
            "curl -H 'X-Thing: " + SECRET + "' https://example.invalid",
            Base64.getEncoder().encodeToString(("svc:" + SECRET).getBytes(StandardCharsets.UTF_8)),
        };
        List<String> texts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            StringBuilder text = new StringBuilder();
            int parts = 1 + random.nextInt(40);
            for (int part = 0; part < parts; part++) {
                boolean secret = random.nextInt(10) == 0;
                String[] pool = secret ? secretShapes : clean;
                text.append(pool[random.nextInt(pool.length)]).append('\n');
            }
            texts.add(text.toString());
        }

        return texts;
    }

    /** Whether any Base64 run in the text still decodes to bytes containing the value. */
    private static boolean decodableSecret(String text) {
        Map<String, Base64.Decoder> decoders = new LinkedHashMap<>();
        decoders.put("[A-Za-z0-9+/]{4,}={0,2}", Base64.getMimeDecoder());
        decoders.put("[A-Za-z0-9_-]{4,}", Base64.getUrlDecoder());
        for (Map.Entry<String, Base64.Decoder> decoder : decoders.entrySet()) {
            var matcher = java.util.regex.Pattern.compile(decoder.getKey()).matcher(text);
            while (matcher.find()) {
                String run = matcher.group();
                // Try each start alignment, the way an attacker would slice a partially masked run.
                for (int offset = 0; offset < 4 && offset < run.length(); offset++) {
                    String slice = run.substring(offset).replace("=", "");
                    slice = slice.substring(0, slice.length() - slice.length() % 4);
                    try {
                        String decoded = new String(decoder.getValue().decode(slice), StandardCharsets.ISO_8859_1);
                        if (decoded.contains(SECRET)) {

                            return true;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Not Base64 at this alignment.
                    }
                }
            }
        }

        return false;
    }
}
