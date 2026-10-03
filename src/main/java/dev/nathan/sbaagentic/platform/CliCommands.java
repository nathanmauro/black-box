package dev.nathan.sbaagentic.platform;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Dependency-free command discovery, usable before Spring or configuration is initialized. */
public final class CliCommands {
    private record Command(String name, String usage, String description) {}

    private static final List<Command> COMMANDS = List.of(
            new Command("doctor", "doctor", "Show storage statistics and optional model/index health."),
            new Command("sessions", "sessions [--limit=25]", "List recent recorded sessions."),
            new Command("search", "search <query> [--limit=25] (or search --q=<query>)", "Search recorded events."),
            new Command(
                    "ingest",
                    "ingest [--text=<note>] [--source=manual] [--session=<id>] [--type=ManualCapture]\n"
                            + "      [--role=user] [--cwd=<path>] [--turn=<id>] [--tool=<name>] [--title=<title>]",
                    "Capture an event. Explicit --text bypasses stdin, including --text= for no text. Otherwise redirected stdin is read through EOF (UTF-8, at most 1 MiB); an attached console is not read. Cwd defaults to the working directory."),
            new Command(
                    "embeddings-backfill",
                    "embeddings-backfill [--apply] [--batch-size=100] [--progress-every=250]",
                    "Preview missing memory embeddings; --apply writes the backfill."),
            new Command(
                    "summarize", "summarize <session-id>", "Summarize a recorded session with the configured backend."),
            new Command(
                    "summarize-missing", "summarize-missing [--limit=10]", "Summarize sessions that lack a summary."));

    private CliCommands() {}

    public static List<String> names() {

        return COMMANDS.stream().map(Command::name).toList();
    }

    public static boolean isCommand(String value) {

        return command(value) != null;
    }

    /** Null means normal dispatch; literal values such as --text=--help are never help flags. */
    public static String helpFor(String[] args) {
        if (args.length == 0)

            return null;

        if (args[0].equals("help")) {
            if (args.length > 1 && !args[1].startsWith("-")) {
                Command command = command(args[1]);
                if (command == null) throw new IllegalArgumentException("Unknown command: " + args[1]);

                return usage(command);
            }

            return usage();
        }
        boolean help = Arrays.stream(args).anyMatch(arg -> arg.equals("--help") || arg.equals("-h"));
        if (!help)

            return null;
        Command command = command(args[0]);
        if (command != null)

            return usage(command);

        // An unknown leading command still reaches the bootstrap's existing rejection guard.
        return args[0].startsWith("--") || args[0].equals("-h") ? usage() : null;
    }

    public static String usage() {

        return "Usage: java -jar <application.jar> [command] [options]\n\n"
                + COMMANDS.stream().map(command -> "  " + command.usage()).collect(Collectors.joining("\n"))
                + "\n\nRun without a command to start the HTTP server.\n"
                + "Use --help, -h, help, or <command> --help for help without starting the application.\n";
    }

    private static String usage(Command command) {

        return "Usage: java -jar <application.jar> " + command.usage() + "\n\n" + command.description()
                + "\nUse --help or -h to show this help without running the command.\n";
    }

    private static Command command(String value) {

        return COMMANDS.stream()
                .filter(command -> command.name().equals(value))
                .findFirst()
                .orElse(null);
    }
}
