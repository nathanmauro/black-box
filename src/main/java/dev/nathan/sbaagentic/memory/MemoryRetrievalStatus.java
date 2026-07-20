package dev.nathan.sbaagentic.memory;

public record MemoryRetrievalStatus(boolean enabled, boolean available, String detail) {

    public static MemoryRetrievalStatus available(String detail) {
        return new MemoryRetrievalStatus(true, true, detail);
    }

    public static MemoryRetrievalStatus unavailable(String detail) {
        return new MemoryRetrievalStatus(true, false, detail);
    }

    public static MemoryRetrievalStatus disabled(String detail) {
        return new MemoryRetrievalStatus(false, false, detail);
    }
}
