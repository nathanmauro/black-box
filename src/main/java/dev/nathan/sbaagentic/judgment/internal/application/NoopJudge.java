package dev.nathan.sbaagentic.judgment.internal.application;

import java.util.Optional;

import dev.nathan.sbaagentic.judgment.internal.application.port.Judge;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgeStats;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;

public class NoopJudge implements Judge {

    @Override
    public Optional<Judgment> judge(BeatState state) {
        return Optional.empty();
    }

    @Override
    public JudgeStats stats() {
        return JudgeStats.empty();
    }
}
