package dev.nathan.sbaagentic.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

@Component
public class RunnerConfigLoader {

    private static final String CONFIG_ENV = "SBA_RUNNER_CONFIG";
    private static final String EXAMPLE_PATH = "docs/runner-config.example.json";

    private final ObjectMapper objectMapper;

    public RunnerConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RunnerConfig load() {
        return load(System.getenv(CONFIG_ENV), System.getProperty("user.home"));
    }

    RunnerConfig load(String configuredPath, String userHome) {
        Path envPath;
        Path defaultPath;
        try {
            envPath = configuredPath == null || configuredPath.isBlank()
                    ? null
                    : Path.of(configuredPath).toAbsolutePath().normalize();
            defaultPath = Path.of(userHome, ".blackbox", "runner.json").toAbsolutePath().normalize();
        }
        catch (RuntimeException ex) {
            throw new RunnerConfigException("Runner config path is invalid. Set " + CONFIG_ENV
                    + " to a readable JSON file or copy " + EXAMPLE_PATH + " to ~/.blackbox/runner.json.", ex);
        }
        if (envPath != null && !Files.isRegularFile(envPath)) {
            throw new RunnerConfigException("Runner config not found at " + envPath + ", which " + CONFIG_ENV
                    + " points to. Fix the path or unset " + CONFIG_ENV
                    + " to fall back to " + defaultPath + ".");
        }
        Path selected = envPath != null
                ? envPath
                : Files.isRegularFile(defaultPath) ? defaultPath : null;

        if (selected == null) {
            String checked = envPath == null
                    ? defaultPath.toString()
                    : envPath + " and " + defaultPath;
            throw new RunnerConfigException("Runner config not found. Checked " + checked
                    + ". Copy " + EXAMPLE_PATH + " to one of those paths and edit it for this machine.");
        }

        try {
            RunnerConfig config = objectMapper.readValue(Files.readString(selected), RunnerConfig.class);
            if (config.repos() == null || config.repos().isEmpty()) {
                throw new RunnerConfigException("Runner config " + selected
                        + " must define at least one allowlisted repo in 'repos'. Copy " + EXAMPLE_PATH
                        + " as a valid starting point.");
            }
            return config;
        }
        catch (RunnerConfigException ex) {
            throw ex;
        }
        catch (IOException | RuntimeException ex) {
            throw new RunnerConfigException("Unable to parse runner config " + selected + ": "
                    + message(ex) + ". Fix the JSON or replace it with a copy of " + EXAMPLE_PATH + ".", ex);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
