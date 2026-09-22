package dev.nathan.sbaagentic.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.SbaAgenticApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ApplicationModuleStructureTest {

    private final ApplicationModules modules = ApplicationModules.of(SbaAgenticApplication.class);

    @Test
    void verifiesTheClosedApplicationModuleGraph() {
        modules.verify();
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "ask",
                        "judgment",
                        "memory",
                        "platform",
                        "project",
                        "query",
                        "recording",
                        "runner",
                        "summary",
                        "workflow");
        new Documenter(modules).writeModulesAsPlantUml();
    }
}
