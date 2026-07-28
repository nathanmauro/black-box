package dev.nathan.sbaagentic.memory.internal.application;

public class TextEmbeddingUnavailable extends RuntimeException {

    public TextEmbeddingUnavailable(String message) {
        super(message);
    }

    public TextEmbeddingUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
