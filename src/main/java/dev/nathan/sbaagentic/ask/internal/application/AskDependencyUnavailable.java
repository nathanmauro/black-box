package dev.nathan.sbaagentic.ask.internal.application;

public class AskDependencyUnavailable extends RuntimeException {

    public AskDependencyUnavailable(String message) {
        super(message);
    }
}
