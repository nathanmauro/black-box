package dev.nathan.sbaagentic.project.internal.application.port;

import java.time.Duration;
import java.util.List;

public interface CommandLauncher {

    void launch(List<String> command, Duration timeout);
}
