package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerConfigException;
import dev.nathan.sbaagentic.runner.RunnerConfigLoader;
import dev.nathan.sbaagentic.runner.RunnerDaemon;

import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class RunnerCli {

    private final RunnerConfigLoader configLoader;
    private final RunnerDaemon daemon;

    public RunnerCli(RunnerConfigLoader configLoader, RunnerDaemon daemon) {
        this.configLoader = configLoader;
        this.daemon = daemon;
    }

    public void run(ApplicationArguments args) {
        RunnerConfig config;
        try {
            config = configLoader.load();
        }
        catch (RunnerConfigException ex) {
            System.err.println("blackbox-runner: " + ex.getMessage());
            System.exit(1);
            return;
        }
        daemon.run(config);
    }
}
