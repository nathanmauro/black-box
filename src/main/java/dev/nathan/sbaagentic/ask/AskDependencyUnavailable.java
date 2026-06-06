package dev.nathan.sbaagentic.ask;

public class AskDependencyUnavailable extends RuntimeException {

    public AskDependencyUnavailable(String message) {
        super(message);
    }
}
