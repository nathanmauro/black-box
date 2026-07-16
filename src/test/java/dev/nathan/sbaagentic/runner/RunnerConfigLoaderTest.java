package dev.nathan.sbaagentic.runner;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerConfigLoaderTest {

    @TempDir
    Path tempDir;

    private final RunnerConfigLoader loader = new RunnerConfigLoader(new ObjectMapper());

    @Test
    void missingConfigExplainsWhereToCreateIt() {
        assertThatThrownBy(() -> loader.load(null, tempDir.toString()))
                .isInstanceOf(RunnerConfigException.class)
                .hasMessageContaining(tempDir.resolve(".blackbox/runner.json").toString())
                .hasMessageContaining("docs/runner-config.example.json");
    }

    @Test
    void validConfigParsesSnakeCaseAndDefaultsEnabledToTrue() throws Exception {
        Path configPath = tempDir.resolve(".blackbox/runner.json");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
                {
                  "concurrency": 3,
                  "engines": [
                    {"id":"codex","model":"gpt-5.6-sol","effort":"xhigh"},
                    {"id":"grok","model":"grok-4.5-fast","effort":"high","enabled":false}
                  ],
                  "notify": "terminal-notifier {msg}",
                  "repos": [
                    {"path":"/tmp/repo","push":true,"auto_merge":true,"verify":"mvn test","danger":""}
                  ]
                }
                """);

        RunnerConfig config = loader.load(null, tempDir.toString());

        assertThat(config.concurrency()).isEqualTo(3);
        assertThat(config.engines().getFirst().enabled()).isTrue();
        assertThat(config.engines().get(1).enabled()).isFalse();
        assertThat(config.repos().getFirst().autoMerge()).isTrue();
    }
}
