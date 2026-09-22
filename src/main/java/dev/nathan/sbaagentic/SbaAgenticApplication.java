package dev.nathan.sbaagentic;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
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

    private static final Set<String> CLI_COMMANDS = Set.of(
            "doctor",
            "embeddings-backfill",
            "ingest",
            "runner",
            "search",
            "sessions",
            "summarize",
            "summarize-missing");

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SbaAgenticApplication.class);
        if (args.length > 0 && CLI_COMMANDS.contains(args[0])) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(Map.of(
                    "spring.main.banner-mode", "off",
                    "logging.level.root", "ERROR"));
        }
        application.run(args);
    }
}
