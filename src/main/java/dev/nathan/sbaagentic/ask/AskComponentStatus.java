package dev.nathan.sbaagentic.ask;

public record AskComponentStatus(boolean enabled, boolean available, String detail) {

    public static AskComponentStatus available(String detail) {
        return new AskComponentStatus(true, true, detail);
    }

    public static AskComponentStatus unavailable(String detail) {
        return new AskComponentStatus(true, false, detail);
    }

    public static AskComponentStatus disabled(String detail) {
        return new AskComponentStatus(false, false, detail);
    }
}
