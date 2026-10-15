package dev.nathan.sbaagentic.summary.internal.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Keeps transcript and summary inputs inside the local model's context window without a live model. */
@DisplayName("Summarize long sessions within the local model's input budget")
class LocalAiClientChunkingTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("fittingTranscripts")
    @DisplayName("Preserve transcripts that already fit")
    void clampPreservesTextWithinBudget(String scenario, String text, int budget) {
        assertThat(LocalAiClient.clampToBudget(text, budget)).isEqualTo(text);
    }

    static Stream<Arguments> fittingTranscripts() {

        return Stream.of(
                Arguments.of("A short transcript", "a short transcript", 12_000),
                Arguments.of("A transcript exactly at the budget", "x".repeat(4_000), 4_000));
    }

    @Test
    @DisplayName("Keep the beginning and end of an oversized transcript and mark the omission")
    void clampKeepsHeadAndTailWithinBudgetAndMarksTheElision() {
        String head = "HEAD".repeat(200);
        String tail = "TAIL".repeat(200);
        String text = head + "x".repeat(50_000) + tail;

        String clamped = LocalAiClient.clampToBudget(text, 4_000);

        assertThat(clamped.length()).isLessThanOrEqualTo(4_000);
        assertThat(clamped).contains("transcript elided").startsWith("HEAD").endsWith("TAIL");
    }

    @Test
    @DisplayName("Even one character over the budget must be trimmed")
    void clampTrimsTextOneCharOverBudgetToWithinBudget() {
        String text = "x".repeat(4_001);
        String clamped = LocalAiClient.clampToBudget(text, 4_000);
        assertThat(clamped.length()).isLessThanOrEqualTo(4_000);
        assertThat(clamped).contains("transcript elided");
    }

    @Test
    @DisplayName("A missing transcript becomes empty input")
    void clampReturnsEmptyForNull() {
        assertThat(LocalAiClient.clampToBudget(null, 12_000)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedTranscripts")
    @DisplayName("Split oversized transcripts into bounded chunks without losing or reordering text")
    void splitPreservesEveryCharacterWithinBudget(String scenario, String text, int budget) {
        List<String> chunks = LocalAiClient.splitIntoChunks(text, budget);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(budget));
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    static Stream<Arguments> oversizedTranscripts() {

        return Stream.of(
                Arguments.of("Many short lines", "line of session text\n".repeat(5_000), 8_000),
                Arguments.of("No line breaks to split on", "z".repeat(40_000), 6_000),
                // A newline at the cap must not push a chunk to budget + 1.
                Arguments.of("A newline exactly at the budget", ("y".repeat(19) + "\n").repeat(2_000), 4_000));
    }

    @Test
    @DisplayName("A transcript that fits stays in one chunk")
    void splitReturnsSingleChunkWhenItFits() {
        String text = "fits comfortably under budget";
        assertThat(LocalAiClient.splitIntoChunks(text, 12_000)).containsExactly(text);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("summaryParts")
    @DisplayName("Fold summaries into fewer bounded batches while keeping every part in order")
    void batchPreservesEveryPartAndMakesProgress(String scenario, List<String> parts) {
        int budget = 4_000;

        List<String> batches = LocalAiClient.batchByBudget(parts, budget);

        assertThat(batches).hasSizeGreaterThan(1).hasSizeLessThan(parts.size());
        assertThat(batches).allSatisfy(batch -> assertThat(batch.length()).isLessThanOrEqualTo(budget));
        String flattened = String.join("\n\n", batches);
        int cursor = -1;
        for (String part : parts) {
            int at = flattened.indexOf(part, cursor + 1);
            assertThat(at).isGreaterThan(cursor);
            cursor = at;
        }
    }

    static Stream<Arguments> summaryParts() {

        return Stream.of(
                Arguments.of(
                        "Fifty larger summaries",
                        IntStream.range(0, 50)
                                .mapToObj(i -> "part-" + i + "-" + "s".repeat(900))
                                .toList()),
                Arguments.of(
                        "Two hundred smaller summaries",
                        IntStream.range(0, 200)
                                .mapToObj(i -> "p" + i + "-" + "z".repeat(500))
                                .toList()));
    }
}
