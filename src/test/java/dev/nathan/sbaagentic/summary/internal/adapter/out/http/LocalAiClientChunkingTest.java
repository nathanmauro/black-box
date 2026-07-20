package dev.nathan.sbaagentic.summary.internal.adapter.out.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The map-reduce safety net: {@link LocalAiClient#splitIntoChunks} and {@link LocalAiClient#clampToBudget}
 * are what stop an oversized transcript from overflowing the local model's context window (the 400 we used
 * to surface). These are pure functions, so they get covered without a live model.
 */
class LocalAiClientChunkingTest {

    @Test
    void shortTextIsClampedToItself() {
        String text = "a short transcript";
        assertThat(LocalAiClient.clampToBudget(text, 12_000)).isEqualTo(text);
    }

    @Test
    void clampKeepsHeadAndTailWithinBudgetAndMarksTheElision() {
        String head = "HEAD".repeat(200);   // 800 chars
        String tail = "TAIL".repeat(200);   // 800 chars
        String text = head + "x".repeat(50_000) + tail;

        String clamped = LocalAiClient.clampToBudget(text, 4_000);

        assertThat(clamped.length()).isLessThanOrEqualTo(4_000);
        assertThat(clamped).contains("transcript elided");
        assertThat(clamped).startsWith("HEAD");
        assertThat(clamped).endsWith("TAIL");
    }

    @Test
    void splitCoversEveryCharacterInOrder() {
        String text = ("line of session text\n").repeat(5_000); // ~105k chars
        int budget = 8_000;

        List<String> chunks = LocalAiClient.splitIntoChunks(text, budget);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(budget));
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    @Test
    void splitReturnsSingleChunkWhenItFits() {
        String text = "fits comfortably under budget";
        assertThat(LocalAiClient.splitIntoChunks(text, 12_000)).containsExactly(text);
    }

    @Test
    void splitTerminatesEvenWithNoLineBreaks() {
        String text = "z".repeat(40_000); // no newline to break on -> hard cuts, must still terminate
        int budget = 6_000;

        List<String> chunks = LocalAiClient.splitIntoChunks(text, budget);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(budget));
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    // The exact-cap boundary is where the original 400 lived: a chunk one char over budget is the bug.

    @Test
    void clampPassesThroughTextExactlyAtBudget() {
        String text = "x".repeat(4_000);
        assertThat(LocalAiClient.clampToBudget(text, 4_000)).isEqualTo(text);
    }

    @Test
    void clampTrimsTextOneCharOverBudgetToWithinBudget() {
        String text = "x".repeat(4_001);
        String clamped = LocalAiClient.clampToBudget(text, 4_000);
        assertThat(clamped.length()).isLessThanOrEqualTo(4_000);
        assertThat(clamped).contains("transcript elided");
    }

    @Test
    void clampReturnsEmptyForNull() {
        assertThat(LocalAiClient.clampToBudget(null, 12_000)).isEmpty();
    }

    @Test
    void splitChunkEndingExactlyOnNewlineStaysWithinBudget() {
        // A line whose newline lands exactly on the hard cap must not push the chunk to budget+1.
        String text = ("y".repeat(19) + "\n").repeat(2_000); // 20-char lines, newline can hit the cap
        int budget = 4_000;

        List<String> chunks = LocalAiClient.splitIntoChunks(text, budget);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(budget));
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    // batchByBudget is the reduce-step fold: it must keep every part, in order, with no batch over budget.

    @Test
    void batchKeepsEveryPartInOrderWithNoBatchOverBudget() {
        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            parts.add("part-" + i + "-" + "s".repeat(900)); // ~900 chars each, all well under budget
        }
        int budget = 4_000;

        List<String> batches = LocalAiClient.batchByBudget(parts, budget);

        assertThat(batches).hasSizeGreaterThan(1);
        assertThat(batches).allSatisfy(b -> assertThat(b.length()).isLessThanOrEqualTo(budget));
        // Folding must lose nothing and reorder nothing: the parts reappear in sequence across the batches.
        String flattened = String.join("\n\n", batches);
        int cursor = -1;
        for (String part : parts) {
            int at = flattened.indexOf(part, cursor + 1);
            assertThat(at).isGreaterThan(cursor);
            cursor = at;
        }
    }

    @Test
    void batchFoldsManyPartsFewerThanInput() {
        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            parts.add("p" + i + "-" + "z".repeat(500));
        }
        List<String> batches = LocalAiClient.batchByBudget(parts, 4_000);
        // The whole point of folding: the next round has strictly fewer items to summarize.
        assertThat(batches.size()).isLessThan(parts.size());
    }
}
