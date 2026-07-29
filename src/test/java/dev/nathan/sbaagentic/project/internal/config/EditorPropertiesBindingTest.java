package dev.nathan.sbaagentic.project.internal.config;

import java.time.Duration;

import dev.nathan.sbaagentic.project.EditorProperties;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EditorPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProjectConfiguration.class)
            .withPropertyValues(
                    "sba.editor.enabled=true",
                    "sba.editor.command=/bin/echo",
                    "sba.editor.allowlist=/bin/echo,/usr/bin/false",
                    "sba.editor.timeout=2s");

    @Test
    void bindsAllowlistAndTimeoutOverrides() {
        contextRunner.run(context -> {
            EditorProperties properties = context.getBean(EditorProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getCommand()).isEqualTo("/bin/echo");
            assertThat(properties.getAllowlist()).containsExactly("/bin/echo", "/usr/bin/false");
            assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
        });
    }
}
