package dev.nathan.sbaagentic.judgment.internal.application.port;

import java.util.Optional;

import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;

public interface Judge {

    Optional<Judgment> judge(BeatState state);

    JudgeStats stats();
}
