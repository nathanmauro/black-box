package dev.nathan.sbaagentic.runner.engine;

import dev.nathan.sbaagentic.runner.EngineConfig;
import java.io.File;
import java.util.List;

public interface Engine {

    String id();

    List<String> command(String prompt, EngineConfig config, File worktreeDir);
}
