package dev.nathan.sbaagentic.runner.gate;

import java.util.List;

public record GateResult(
        boolean pass,
        List<String> findings,
        String resolvedVerify,
        GateAdvisor.GateAdvisorNote advisorNote,
        String mode) {

    public GateResult {
        findings = List.copyOf(findings);
    }

    public GateResult(
            boolean pass,
            List<String> findings,
            String resolvedVerify,
            GateAdvisor.GateAdvisorNote advisorNote) {
        this(pass, findings, resolvedVerify, advisorNote, null);
    }
}
