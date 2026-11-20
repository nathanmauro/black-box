package dev.nathan.sbaagentic.memory.eval;

import dev.nathan.sbaagentic.memory.internal.application.TextEmbeddingUnavailable;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * A deterministic, network-free stand-in for the Ollama embedder. Text is tokenized, mapped
 * through a concept lexicon (so a paraphrase lands on the same dimensions as the original), and
 * feature-hashed into a fixed-width L2-normalized vector.
 *
 * <p>This measures how the recall pipeline ranks, fuses, gates, and scopes semantic candidates. It
 * does not measure the quality of any real embedding model: a paraphrase only matches when the
 * lexicon says so. Concept tokens carry full weight; unmapped words carry a small weight so
 * surface overlap still contributes, the way it does in a real model.
 */
final class HashingTextEmbedder implements TextEmbedder {

    static final String MODEL = "bench-hashing-v1";
    static final int DIMENSIONS = 256;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final double CONCEPT_WEIGHT = 1.0;
    private static final double SURFACE_WEIGHT = 0.25;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "for", "in", "on", "at", "by", "with", "is", "are", "was",
            "were", "be", "do", "does", "did", "we", "our", "it", "its", "this", "that", "so", "after", "before", "how",
            "why", "what", "when", "which", "every", "each", "into", "from", "as", "until", "then", "only", "first",
            "s", "keep", "use", "next", "now");

    private final Map<String, String> lexicon;
    private final AtomicBoolean available = new AtomicBoolean(true);

    HashingTextEmbedder(Map<String, String> lexicon) {
        this.lexicon = Map.copyOf(lexicon);
    }

    void available(boolean value) {
        available.set(value);
    }

    @Override
    public EmbeddingVector embedDocument(String text) {

        return embed(text);
    }

    @Override
    public EmbeddingVector embedQuery(String text) {

        return embed(text);
    }

    @Override
    public String documentContentHash(String text) {

        return EmbeddingVector.contentHash(text);
    }

    @Override
    public boolean available() {

        return available.get();
    }

    @Override
    public String model() {

        return MODEL;
    }

    @Override
    public int dimensions() {

        return DIMENSIONS;
    }

    private EmbeddingVector embed(String text) {
        if (!available.get()) {
            throw new TextEmbeddingUnavailable("benchmark embedder switched off");
        }

        return new EmbeddingVector(MODEL, vectorFor(text));
    }

    float[] vectorFor(String text) {
        double[] accumulator = new double[DIMENSIONS];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String raw : TOKEN_SPLIT.split(normalized)) {
            if (raw.isEmpty() || STOP_WORDS.contains(raw)) {
                continue;
            }
            String token = stem(raw);
            String concept = lexicon.get(token);
            if (concept != null) {
                accumulator[bucket("concept:" + concept)] += CONCEPT_WEIGHT;
            } else {
                accumulator[bucket("surface:" + token)] += SURFACE_WEIGHT;
            }
        }

        return normalize(accumulator);
    }

    /** A deliberately crude plural/tense stemmer; the lexicon keys use the same stems. */
    static String stem(String token) {
        if (token.length() > 4 && token.endsWith("ies")) {

            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 4 && token.endsWith("ing")) {

            return token.substring(0, token.length() - 3);
        }
        if (token.length() > 3 && token.endsWith("ed")) {

            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")) {

            return token.substring(0, token.length() - 1);
        }

        return token;
    }

    /** FNV-1a so bucket assignment never depends on JVM hash seeds or String.hashCode changes. */
    private static int bucket(String feature) {
        int hash = 0x811c9dc5;
        for (byte b : feature.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }

        return Math.floorMod(hash, DIMENSIONS);
    }

    private static float[] normalize(double[] accumulator) {
        double norm = 0.0;
        for (double value : accumulator) {
            norm += value * value;
        }
        float[] vector = new float[accumulator.length];
        if (norm == 0.0) {
            // An all-zero vector has no direction; a fixed unit axis keeps cosine well defined.
            vector[0] = 1.0f;

            return vector;
        }
        double length = Math.sqrt(norm);
        for (int i = 0; i < accumulator.length; i++) {
            vector[i] = (float) (accumulator[i] / length);
        }

        return vector;
    }
}
