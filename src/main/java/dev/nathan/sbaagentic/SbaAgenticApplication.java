package dev.nathan.sbaagentic;

import java.util.Set;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SbaAgenticApplication {

    private static final Set<String> CLI_COMMANDS = Set.of(
            "doctor",
            "ingest",
            "search",
            "sessions",
            "summarize");

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
