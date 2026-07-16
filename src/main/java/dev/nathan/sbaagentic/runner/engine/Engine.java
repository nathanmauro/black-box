package dev.nathan.sbaagentic.runner.engine;

import java.io.File;
import java.util.List;

import dev.nathan.sbaagentic.runner.EngineConfig;

public interface Engine {

    String id();

    List<String> command(String prompt, EngineConfig config, File worktreeDir);
}
