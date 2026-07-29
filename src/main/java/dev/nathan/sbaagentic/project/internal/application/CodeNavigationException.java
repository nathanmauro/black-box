package dev.nathan.sbaagentic.project.internal.application;

public class CodeNavigationException extends RuntimeException {

    private final CodeNavigationError code;

    public CodeNavigationException(CodeNavigationError code, String message) {
        super(message);
        this.code = code;
    }

    public CodeNavigationException(CodeNavigationError code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public CodeNavigationError code() {
        return code;
    }
}
