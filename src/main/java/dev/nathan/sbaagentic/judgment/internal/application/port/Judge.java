package dev.nathan.sbaagentic.judgment.internal.application.port;

import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import java.util.Optional;

public interface Judge {

    Optional<Judgment> judge(BeatState state);

    JudgeStats stats();
}
