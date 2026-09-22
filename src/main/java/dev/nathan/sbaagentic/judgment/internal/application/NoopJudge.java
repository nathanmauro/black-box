package dev.nathan.sbaagentic.judgment.internal.application;

import dev.nathan.sbaagentic.judgment.internal.application.port.Judge;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgeStats;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import java.util.Optional;

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
