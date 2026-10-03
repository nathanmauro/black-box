package dev.nathan.sbaagentic.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CliCommandsTest {
    @Test
    void helpListsExactlyTheImplementedCommands() {
        assertThat(CliCommands.names())
                .containsExactly(
                        "doctor",
                        "sessions",
                        "search",
                        "ingest",
                        "embeddings-backfill",
                        "summarize",
                        "summarize-missing");
        for (String command : CliCommands.names()) {
            assertThat(CliCommands.isCommand(command)).isTrue();
            assertThat(CliCommands.usage()).contains("  " + command);
            assertThat(CliCommands.helpFor(new String[] {command, "--help"})).contains(command, "Usage:");
        }
        assertThat(CliCommands.isCommand("runner")).isFalse();
    }

    @Test
    void literalValuesAndNormalServerOrCommandArgumentsRemainNormalDispatch() {
        for (String[] args : List.of(
                new String[] {},
                new String[] {"--server.port=0"},
                new String[] {"ingest", "--text=--help"},
                new String[] {"search", "--q=--help"},
                new String[] {"search", "help"},
                new String[] {"search", "--q=-h"},
                new String[] {"sessions"})) {
            assertThat(CliCommands.helpFor(args)).isNull();
        }
    }

    @Test
    void helpAliasesAndKnownCommandHelpShareDefinitions() {
        for (String flag : List.of("--help", "-h", "help"))
            assertThat(CliCommands.helpFor(new String[] {flag})).isEqualTo(CliCommands.usage());
        assertThat(CliCommands.helpFor(new String[] {"help", "ingest"}))
                .isEqualTo(CliCommands.helpFor(new String[] {"ingest", "--help"}));
        assertThat(CliCommands.helpFor(new String[] {"--server.port=1", "--help"}))
                .isEqualTo(CliCommands.usage());
        assertThat(CliCommands.helpFor(new String[] {"unknown", "--help"})).isNull();
        assertThatThrownBy(() -> CliCommands.helpFor(new String[] {"help", "unknown"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown command: unknown");
    }
}
