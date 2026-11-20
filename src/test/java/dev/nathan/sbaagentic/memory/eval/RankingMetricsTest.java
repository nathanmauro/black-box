package dev.nathan.sbaagentic.memory.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ranking metrics match hand-computed IR reference values")
class RankingMetricsTest {

    private static final Map<String, Integer> GRADES = Map.of("a", 3, "b", 2, "c", 0, "d", 1);

    @Test
    void precisionCountsEmptySlotsAsMisses() {
        assertThat(RankingMetrics.precisionAtK(List.of("a", "c", "b"), GRADES, 3))
                .isCloseTo(2.0 / 3.0, within(1e-12));
        assertThat(RankingMetrics.precisionAtK(List.of("a"), GRADES, 5)).isCloseTo(0.2, within(1e-12));
        assertThat(RankingMetrics.precisionAtK(List.of(), GRADES, 5)).isZero();
    }

    @Test
    void duplicateIdsCannotInflatePrecision() {
        assertThat(RankingMetrics.precisionAtK(List.of("a", "a", "a"), GRADES, 3))
                .isCloseTo(1.0 / 3.0, within(1e-12));
    }

    @Test
    void recallIsTheShareOfRelevantItemsRetrieved() {
        assertThat(RankingMetrics.recallAtK(List.of("a", "c", "x"), GRADES, 10)).isCloseTo(1.0 / 3.0, within(1e-12));
        assertThat(RankingMetrics.recallAtK(List.of("a"), Map.of(), 10)).isZero();
    }

    @Test
    void reciprocalRankUsesTheFirstRelevantPosition() {
        assertThat(RankingMetrics.reciprocalRank(List.of("c", "x", "d"), GRADES))
                .isCloseTo(1.0 / 3.0, within(1e-12));
        assertThat(RankingMetrics.reciprocalRank(List.of("c", "x"), GRADES)).isZero();
    }

    @Test
    void ndcgMatchesAHandComputedReference() {
        // DCG = (2^1-1)/log2(2) + (2^0-1)/log2(3) + (2^3-1)/log2(4) = 1 + 0 + 3.5 = 4.5
        // IDCG = 7/1 + 3/log2(3) + 1/2 = 7.5 + 1.892789...
        double idcg = 7.0 + 3.0 / (Math.log(3) / Math.log(2)) + 0.5;
        assertThat(RankingMetrics.ndcgAtK(List.of("d", "c", "a"), GRADES, 3)).isCloseTo(4.5 / idcg, within(1e-12));
    }

    @Test
    void ndcgIsOneForTheIdealOrderingAndZeroWithoutJudgments() {
        assertThat(RankingMetrics.ndcgAtK(List.of("a", "b", "d"), GRADES, 10)).isCloseTo(1.0, within(1e-12));
        assertThat(RankingMetrics.ndcgAtK(List.of("a"), Map.of(), 10)).isZero();
    }

    @Test
    void nearestRankPercentileReturnsAnObservedSample() {
        List<Long> samples = List.of(15L, 20L, 35L, 40L, 50L);
        assertThat(RankingMetrics.percentile(samples, 50)).isEqualTo(35L);
        assertThat(RankingMetrics.percentile(samples, 30)).isEqualTo(20L);
        assertThat(RankingMetrics.percentile(samples, 100)).isEqualTo(50L);
        assertThat(RankingMetrics.percentile(List.of(7L), 99)).isEqualTo(7L);
    }

    @Test
    void invalidArgumentsFailLoudly() {
        assertThatThrownBy(() -> RankingMetrics.percentile(List.of(), 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RankingMetrics.percentile(List.of(1L), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RankingMetrics.precisionAtK(List.of(), GRADES, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
