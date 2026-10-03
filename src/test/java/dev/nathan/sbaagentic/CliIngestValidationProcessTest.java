package dev.nathan.sbaagentic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Actual CLI capture -> query contract with private storage, held-open stdin and no providers. */
class CliIngestValidationProcessTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    static Stream<Arguments> invalidCaptures() {

        return Stream.of(
                Arguments.of(List.of("ingest", "important note"), "ingest does not accept positional arguments"),
                Arguments.of(
                        List.of("ingest", "--session", "intended-session", "--text=note"),
                        "ingest does not accept positional arguments"),
                Arguments.of(List.of("ingest", "--source=", "--text=note"), "--source must not be blank"),
                Arguments.of(List.of("ingest", "--session= \t "), "--session must not be blank"),
                Arguments.of(List.of("ingest", "--type="), "--type must not be blank"),
                Arguments.of(List.of("ingest", "--source"), "--source requires a value"),
                Arguments.of(List.of("ingest", "--session"), "--session requires a value"),
                Arguments.of(List.of("ingest", "--tool"), "--tool requires a value"),
                Arguments.of(List.of("ingest", "--text"), "--text requires a value"));
    }

    @ParameterizedTest
    @MethodSource("invalidCaptures")
    void invalidCaptureExitsWithNoWritesWhileStdinRemainsOpen(List<String> args, String message) throws Exception {
        Fixture fixture = fixture();
        Result result = run(fixture, args);
        assertThat(result.exit()).as(result.output()).isNotZero();
        assertThat(result.output()).contains(message).doesNotContain("\"eventId\"");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.db())) {
            for (String table :
                    List.of("agent_events", "agent_sessions", "event_capture_receipts", "event_stream_positions")) {
                try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).as(table).isZero();
                }
            }
        }
    }

    static Stream<Arguments> validCaptures() {

        return Stream.of(
                Arguments.of(List.of("ingest", "--text=Searchable fixture note"), "manual", "ManualCapture", null),
                Arguments.of(
                        List.of(
                                "ingest",
                                "--source=codex",
                                "--session=intended-session",
                                "--type=Observation",
                                "--text=Searchable fixture note",
                                "--turn=",
                                "--cwd=",
                                "--tool="),
                        "codex",
                        "Observation",
                        "intended-session"));
    }

    @ParameterizedTest
    @MethodSource("validCaptures")
    void acceptedCaptureExitsAndCanBeQueried(List<String> args, String source, String type, String session)
            throws Exception {
        Fixture fixture = fixture();
        Result capture = run(fixture, args);
        assertThat(capture.exit()).as(capture.output()).isZero();
        JsonNode acknowledgement = mapper.readTree(capture.output());
        String eventId = acknowledgement.path("eventId").asText();
        assertThat(eventId).isNotBlank();
        assertThat(acknowledgement.path("source").asText()).isEqualTo(source);
        assertThat(acknowledgement.path("eventType").asText()).isEqualTo(type);
        String actualSession = acknowledgement.path("clientSessionId").asText();
        if (session == null) assertThat(actualSession).startsWith("manual-");
        else assertThat(actualSession).isEqualTo(session);
        Result search = run(fixture, List.of("search", "session:" + actualSession + " Searchable fixture"));
        assertThat(search.exit()).as(search.output()).isZero();
        JsonNode hits = mapper.readTree(search.output()).path("local");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path("id").asText()).isEqualTo(eventId);
        assertThat(hits.get(0).path("text").asText()).isEqualTo("Searchable fixture note");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.db());
                var rows = connection
                        .createStatement()
                        .executeQuery("SELECT source, client_session_id, event_type, text FROM agent_events")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("source")).isEqualTo(source);
            assertThat(rows.getString("client_session_id")).isEqualTo(actualSession);
            assertThat(rows.getString("event_type")).isEqualTo(type);
            assertThat(rows.getString("text")).isEqualTo("Searchable fixture note");
            assertThat(rows.next()).isFalse();
        }
    }

    private Result run(Fixture fixture, List<String> args) throws Exception {
        Path output = fixture.directory().resolve(UUID.randomUUID() + ".log");
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + fixture.home(),
                "-Djava.io.tmpdir=" + fixture.directory(),
                "-cp",
                classpath,
                SbaAgenticApplication.class.getName()));
        command.addAll(args);
        command.addAll(List.of(
                "--spring.config.location=classpath:/application.yml",
                "--spring.datasource.url=jdbc:sqlite:" + fixture.db(),
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--sba.auth.enabled=false",
                "--sba.editor.enabled=false",
                "--sba.local-ai.enabled=false",
                "--sba.summary.backend=local",
                "--sba.elasticsearch.enabled=false",
                "--sba.memory.embedding.enabled=false",
                "--sba.ask.embedding-enabled=false",
                "--sba.judge.enabled=false",
                "--sba.exports.targets[0].enabled=false",
                "--sba.storage.retire-workflow=false",
                "--sba.transcript.claude-roots[0]=" + fixture.home().resolve("unavailable"),
                "--sba.transcript.codex-roots[0]=" + fixture.home().resolve("unavailable")));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(fixture.directory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().clear();
        builder.environment()
                .putAll(Map.of(
                        "HOME",
                        fixture.home().toString(),
                        "TMPDIR",
                        fixture.directory().toString(),
                        "LANG",
                        "C.UTF-8"));
        Process process = builder.start();
        try {
            // Deliberately retain the input pipe: invalid arguments and explicit text must not wait for EOF.
            assertThat(process.waitFor(15, TimeUnit.SECONDS))
                    .as("CLI exits with held-open stdin: %s", args)
                    .isTrue();

            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            try {
                process.getOutputStream().close();
            } finally {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                    assertThat(process.waitFor(5, TimeUnit.SECONDS))
                            .as("Owned CLI fixture reaped")
                            .isTrue();
                }
            }
        }
    }

    private Fixture fixture() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve(UUID.randomUUID().toString()));

        return new Fixture(
                directory, Files.createDirectory(directory.resolve("home")), directory.resolve("fixture.db"));
    }

    private record Fixture(Path directory, Path home, Path db) {}

    private record Result(int exit, String output) {}
}
