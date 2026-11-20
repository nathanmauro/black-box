package dev.nathan.sbaagentic.memory.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standard offline information-retrieval metrics over one ranked result list and graded relevance
 * judgments. A judgment grade of zero (or an absent key) means "not relevant"; any positive grade
 * counts as relevant for the binary metrics and as gain for NDCG.
 *
 * <p>Duplicate ids in a ranking are counted once, at their first position, so a surface that
 * repeats a hit cannot inflate precision.
 */
final class RankingMetrics {

    private RankingMetrics() {}

    /** Fraction of the top {@code k} slots holding a relevant item; empty slots count as misses. */
    static double precisionAtK(List<String> ranked, Map<String, Integer> grades, int k) {
        requirePositive(k);
        List<String> top = topK(ranked, k);
        long hits = top.stream().filter(id -> grade(grades, id) > 0).count();

        return (double) hits / k;
    }

    /** Fraction of all relevant items that appear in the top {@code k}. */
    static double recallAtK(List<String> ranked, Map<String, Integer> grades, int k) {
        requirePositive(k);
        long relevant = grades.values().stream().filter(grade -> grade > 0).count();
        if (relevant == 0) {

            return 0.0;
        }
        long hits = topK(ranked, k).stream().filter(id -> grade(grades, id) > 0).count();

        return (double) hits / relevant;
    }

    /** {@code 1 / rank} of the first relevant item, or zero when none is returned. */
    static double reciprocalRank(List<String> ranked, Map<String, Integer> grades) {
        List<String> distinct = distinct(ranked);
        for (int i = 0; i < distinct.size(); i++) {
            if (grade(grades, distinct.get(i)) > 0) {

                return 1.0 / (i + 1);
            }
        }

        return 0.0;
    }

    /**
     * Normalized discounted cumulative gain with exponential gain {@code 2^grade - 1} and a
     * {@code log2(rank + 1)} discount, normalized by the ideal ordering of every judged item.
     */
    static double ndcgAtK(List<String> ranked, Map<String, Integer> grades, int k) {
        requirePositive(k);
        List<String> top = topK(ranked, k);
        double dcg = 0.0;
        for (int i = 0; i < top.size(); i++) {
            dcg += gain(grade(grades, top.get(i))) * discount(i);
        }

        List<Integer> ideal = new ArrayList<>(grades.values());
        ideal.sort(Collections.reverseOrder());
        double idcg = 0.0;
        for (int i = 0; i < Math.min(k, ideal.size()); i++) {
            idcg += gain(ideal.get(i)) * discount(i);
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /**
     * Nearest-rank percentile: the smallest sample with at least {@code p} percent of samples at or
     * below it. Chosen over interpolation so a reported p99 is always a latency that was observed.
     */
    static long percentile(List<Long> samples, double p) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("percentile of an empty sample");
        }
        if (p <= 0.0 || p > 100.0) {
            throw new IllegalArgumentException("percentile must be in (0, 100]: " + p);
        }
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());

        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static List<String> topK(List<String> ranked, int k) {
        List<String> distinct = distinct(ranked);

        return distinct.subList(0, Math.min(k, distinct.size()));
    }

    private static List<String> distinct(List<String> ranked) {
        Set<String> seen = new HashSet<>();
        List<String> distinct = new ArrayList<>();
        for (String id : ranked) {
            if (id != null && seen.add(id)) {
                distinct.add(id);
            }
        }

        return distinct;
    }

    private static int grade(Map<String, Integer> grades, String id) {
        Integer grade = grades.get(id);

        return grade == null ? 0 : Math.max(grade, 0);
    }

    private static double gain(int grade) {

        return Math.pow(2, grade) - 1;
    }

    private static double discount(int zeroBasedRank) {

        return 1.0 / (Math.log(zeroBasedRank + 2) / Math.log(2));
    }

    private static void requirePositive(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
    }
}
