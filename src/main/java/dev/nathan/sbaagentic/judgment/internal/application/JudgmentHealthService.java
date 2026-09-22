package dev.nathan.sbaagentic.judgment.internal.application;

import dev.nathan.sbaagentic.judgment.JudgmentHealth;
import dev.nathan.sbaagentic.judgment.JudgmentHealthOperations;
import dev.nathan.sbaagentic.judgment.JudgmentProperties;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgeStats;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class JudgmentHealthService implements JudgmentHealthOperations {

    private final JudgmentProperties properties;
    private final ObjectProvider<JudgmentListener> listener;

    public JudgmentHealthService(
            JudgmentProperties properties,
            ObjectProvider<JudgmentListener> listener) {
        this.properties = properties;
        this.listener = listener;
    }

    @Override
    public JudgmentHealth health() {
        JudgmentListener active = listener.getIfAvailable();
        JudgeStats stats = active == null ? JudgeStats.empty() : active.judgeStats();
        return new JudgmentHealth(
                properties.isEnabled(),
                properties.getProvider(),
                "none".equals(properties.getProvider()) ? null : "jev-latest",
                stats.calls(),
                stats.failures(),
                stats.lastLatencyMs(),
                active == null ? 0 : active.queued(),
                active == null ? 0 : active.dropped());
    }
}
