package dev.nathan.sbaagentic.ask.internal.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.memory.MemoryHit;

public final class ReciprocalRankFusion {

    private static final int RRF_K = 60;

    private ReciprocalRankFusion() {
    }

    public static List<MemoryHit> fuse(List<MemoryHit> lexical, List<MemoryHit> vector, int limit) {
        Map<String, MemoryHit> firstHitById = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        addRanking(lexical, firstHitById, scores);
        addRanking(vector, firstHitById, scores);

        List<MemoryHit> fused = new ArrayList<>();
        for (Map.Entry<String, MemoryHit> entry : firstHitById.entrySet()) {
            fused.add(entry.getValue().withScore(scores.getOrDefault(entry.getKey(), 0.0)));
        }
        fused.sort(Comparator
                .comparingDouble(MemoryHit::score)
                .reversed()
                .thenComparing(MemoryHit::id));
        return fused.stream().limit(Math.max(0, limit)).toList();
    }

    private static void addRanking(
            List<MemoryHit> ranking,
            Map<String, MemoryHit> firstHitById,
            Map<String, Double> scores) {
        for (int i = 0; i < ranking.size(); i++) {
            MemoryHit hit = ranking.get(i);
            if (hit == null || hit.id() == null || hit.id().isBlank()) {
                continue;
            }
            firstHitById.putIfAbsent(hit.id(), hit);
            double contribution = 1.0 / (RRF_K + i + 1);
            scores.merge(hit.id(), contribution, Double::sum);
        }
    }
}
