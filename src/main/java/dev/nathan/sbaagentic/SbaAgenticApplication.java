package dev.nathan.sbaagentic;

import dev.nathan.sbaagentic.platform.CliCommands;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SbaAgenticApplication {

    /** Injectable server clock: grammar time tokens resolve against it, and tests can fix it. */
    @Bean
    public Clock clock() {

        return Clock.systemDefaultZone();
    }

    public static void main(String[] args) {
        String help = CliCommands.helpFor(args);
        if (help != null) {
            System.out.print(help);

            return;
        }
        // Unknown/retired commands must never accidentally start an API server or open a database.
        if (args.length > 0 && !args[0].startsWith("--") && !CliCommands.isCommand(args[0])) {
            throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
        SpringApplication application = new SpringApplication(SbaAgenticApplication.class);
        if (args.length > 0 && CliCommands.isCommand(args[0])) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(Map.of(
                    "spring.main.banner-mode", "off",
                    "logging.level.root", "ERROR"));
        }
        application.run(args);
    }
}
