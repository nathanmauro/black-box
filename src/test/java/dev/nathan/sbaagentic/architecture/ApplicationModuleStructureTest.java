package dev.nathan.sbaagentic.architecture;

import dev.nathan.sbaagentic.SbaAgenticApplication;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationModuleStructureTest {

    private final ApplicationModules modules = ApplicationModules.of(SbaAgenticApplication.class);

    @Test
    void verifiesTheClosedApplicationModuleGraph() {
        modules.verify();
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "ask", "memory", "platform", "project",
                        "recording", "runner", "summary", "workflow");
        new Documenter(modules).writeModulesAsPlantUml();
    }
}
