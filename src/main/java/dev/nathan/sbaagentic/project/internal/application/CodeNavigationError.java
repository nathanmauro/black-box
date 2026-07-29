package dev.nathan.sbaagentic.project.internal.application;

public enum CodeNavigationError {
    INVALID_REFERENCE("invalid_reference"),
    OUTSIDE_PROJECT_ROOT("outside_project_root"),
    FILE_MISSING("file_missing"),
    PROJECT_UNRESOLVED("project_unresolved"),
    EDITOR_DISABLED("editor_disabled"),
    REVEAL_UNAVAILABLE("reveal_unavailable");

    private final String type;

    CodeNavigationError(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }
}
