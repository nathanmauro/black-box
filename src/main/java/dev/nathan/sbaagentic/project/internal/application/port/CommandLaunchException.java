package dev.nathan.sbaagentic.project.internal.application.port;

public class CommandLaunchException extends RuntimeException {

    public CommandLaunchException(String message) {
        super(message);
    }

    public CommandLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
