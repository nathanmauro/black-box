package dev.nathan.sbaagentic.runner.run;

public enum RunStage {
    PLAN("plan"),
    BUILD("build"),
    REVIEW("review");

    private final String environmentValue;

    RunStage(String environmentValue) {
        this.environmentValue = environmentValue;
    }

    public String environmentValue() {
        return environmentValue;
    }
}
