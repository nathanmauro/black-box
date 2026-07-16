package dev.nathan.sbaagentic.runner.gate;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class NoOpGateAdvisor implements GateAdvisor {

    @Override
    public GateAdvisorNote advise(String storyBody, List<String> deterministicFindings) {
        return new GateAdvisorNote("", false);
    }
}
