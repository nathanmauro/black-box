package dev.nathan.sbaagentic.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SbaConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SbaConfiguration.class);

    @Test
    void bindsExternalSummaryBackendByDefault() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("sba.summary.backend")).isEqualTo("external");
            assertThat(context.getBean(SbaProperties.class)
                    .getSummary()
                    .getBackend()).isEqualTo("external");
        });
    }

    @Test
    void bindsExplicitLocalSummaryBackendOverride() {
        contextRunner
                .withPropertyValues("SBA_SUMMARY_BACKEND=local")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("sba.summary.backend")).isEqualTo("local");
                    assertThat(context.getBean(SbaProperties.class)
                            .getSummary()
                            .getBackend()).isEqualTo("local");
                });
    }
}
