package dev.nathan.sbaagentic.architecture;

import dev.nathan.sbaagentic.runner.RunnerConfigLoader;

import org.junit.jupiter.api.Test;

import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(module = "runner")
@TestPropertySource(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
class RunnerApplicationModuleTest {

    @Autowired
    RunnerConfigLoader configLoader;

    @Test
    void exposesTheRunnerConfigurationEntryPoint() {
        assertThat(configLoader).isNotNull();
    }
}
