package dev.nathan.sbaagentic.runner;

public class RunnerConfigException extends RuntimeException {

    public RunnerConfigException(String message) {
        super(message);
    }

    public RunnerConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
