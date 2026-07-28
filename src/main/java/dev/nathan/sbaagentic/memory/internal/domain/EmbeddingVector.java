package dev.nathan.sbaagentic.memory.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public record EmbeddingVector(String model, float[] values) {

    public EmbeddingVector {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        Objects.requireNonNull(values, "values");
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    public byte[] toBlob() {
        byte[] blob = new byte[values.length * Float.BYTES];
        for (int index = 0; index < values.length; index++) {
            int bits = Float.floatToIntBits(values[index]);
            int offset = index * Float.BYTES;
            blob[offset] = (byte) bits;
            blob[offset + 1] = (byte) (bits >>> 8);
            blob[offset + 2] = (byte) (bits >>> 16);
            blob[offset + 3] = (byte) (bits >>> 24);
        }
        return blob;
    }

    public static EmbeddingVector fromBlob(String model, byte[] blob) {
        Objects.requireNonNull(blob, "blob");
        if (blob.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("embedding blob length must be a multiple of 4");
        }
        float[] values = new float[blob.length / Float.BYTES];
        for (int index = 0; index < values.length; index++) {
            int offset = index * Float.BYTES;
            int bits = (blob[offset] & 0xff)
                    | ((blob[offset + 1] & 0xff) << 8)
                    | ((blob[offset + 2] & 0xff) << 16)
                    | ((blob[offset + 3] & 0xff) << 24);
            values[index] = Float.intBitsToFloat(bits);
        }
        return new EmbeddingVector(model, values);
    }

    public EmbeddingVector normalized() {
        double magnitudeSquared = 0.0;
        for (float value : values) {
            magnitudeSquared += (double) value * value;
        }
        if (magnitudeSquared == 0.0) {
            return this;
        }
        double magnitude = Math.sqrt(magnitudeSquared);
        float[] normalized = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            normalized[index] = (float) (values[index] / magnitude);
        }
        return new EmbeddingVector(model, normalized);
    }

    public double cosineSimilarity(EmbeddingVector other) {
        Objects.requireNonNull(other, "other");
        if (values.length != other.values.length) {
            throw new IllegalArgumentException("embedding dimensions must match");
        }
        double dot = 0.0;
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        for (int index = 0; index < values.length; index++) {
            dot += (double) values[index] * other.values[index];
            leftMagnitude += (double) values[index] * values[index];
            rightMagnitude += (double) other.values[index] * other.values[index];
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }

    public static String contentHash(String text) {
        Objects.requireNonNull(text, "text");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmbeddingVector that)) {
            return false;
        }
        return model.equals(that.model) && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return 31 * model.hashCode() + Arrays.hashCode(values);
    }
}
