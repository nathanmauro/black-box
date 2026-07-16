package dev.nathan.sbaagentic.runner.gate;

import java.util.List;

/**
 * Seam for future LLM-assisted gate scoring. The v1 implementation remains advisory and never
 * blocks a story on its own.
 */
public interface GateAdvisor {

    GateAdvisorNote advise(String storyBody, List<String> deterministicFindings);

    record GateAdvisorNote(String feedback, boolean blocking) {
    }
}
