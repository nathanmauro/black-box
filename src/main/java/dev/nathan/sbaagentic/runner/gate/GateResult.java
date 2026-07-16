package dev.nathan.sbaagentic.runner.gate;

import java.util.List;

public record GateResult(
        boolean pass,
        List<String> findings,
        String resolvedVerify,
        GateAdvisor.GateAdvisorNote advisorNote) {

    public GateResult {
        findings = List.copyOf(findings);
    }
}
