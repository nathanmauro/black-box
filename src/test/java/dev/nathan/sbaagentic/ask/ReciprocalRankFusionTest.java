package dev.nathan.sbaagentic.ask;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    @Test
    void fusesLexicalAndVectorRankingsByReciprocalRank() {
        MemoryHit lexicalA = hit("a", 12.0);
        MemoryHit lexicalB = hit("b", 8.0);
        MemoryHit vectorC = hit("c", 0.92);
        MemoryHit vectorA = hit("a", 0.72);

        List<MemoryHit> fused = ReciprocalRankFusion.fuse(
                List.of(lexicalA, lexicalB),
                List.of(vectorC, vectorA),
                10);

        assertThat(fused).extracting(MemoryHit::id).containsExactly("a", "c", "b");
        assertThat(fused.getFirst().score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    void keepsFirstHitMetadataWhenTheSameMemoryAppearsInBothRankings() {
        MemoryHit lexical = hit("same", 7.0, "Lexical title", "/memory/lexical.md");
        MemoryHit vector = hit("same", 0.84, "Vector title", "/memory/vector.md");

        List<MemoryHit> fused = ReciprocalRankFusion.fuse(List.of(lexical), List.of(vector), 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.getFirst().title()).isEqualTo("Lexical title");
        assertThat(fused.getFirst().sourcePath()).isEqualTo("/memory/lexical.md");
    }

    private static MemoryHit hit(String id, double score) {
        return hit(id, score, "Title " + id, "/memory/" + id + ".md");
    }

    private static MemoryHit hit(String id, double score, String title, String sourcePath) {
        return new MemoryHit(
                id,
                score,
                title,
                "agent-memory",
                sourcePath,
                "session-" + id,
                "client-" + id,
                "2026-06-01T12:00:00Z",
                "Memory text " + id,
                "Memory snippet " + id);
    }
}
