package dev.nathan.sbaagentic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Actual entry point with private cwd/home, held stdin, disabled providers and no live defaults. */
class CliHelpProcessTest {
    @TempDir
    Path tempDir;

    static Stream<Arguments> helpCommands() {

        return Stream.of(
                Arguments.of(List.of("--help"), true),
                Arguments.of(List.of("-h"), true),
                Arguments.of(List.of("help"), true),
                Arguments.of(List.of("help", "ingest"), true),
                Arguments.of(List.of("doctor", "--help"), true),
                Arguments.of(List.of("sessions", "--help"), true),
                Arguments.of(List.of("search", "--help"), true),
                Arguments.of(List.of("ingest", "--help"), true),
                Arguments.of(List.of("embeddings-backfill", "--help"), true),
                Arguments.of(List.of("summarize", "--help"), true),
                Arguments.of(List.of("summarize-missing", "--help"), true),
                Arguments.of(List.of("ingest", "-h"), true),
                Arguments.of(List.of("--server.address=127.0.0.1", "--help"), true),
                Arguments.of(List.of("--help"), false),
                Arguments.of(List.of("ingest", "--help"), false));
    }

    @ParameterizedTest(name = "{0}, invalid database={1}")
    @MethodSource("helpCommands")
    void helpExitsWithoutStartingSpringReadingStdinOrOpeningStorage(List<String> args, boolean invalidDatabase)
            throws Exception {
        Result result = launch(args, invalidDatabase);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Usage:", "--help");
        assertThat(result.output()).doesNotContain("Starting SbaAgenticApplication", "HikariPool", "Tomcat", "eventId");
        assertThat(result.database()).doesNotExist();
    }

    static Stream<List<String>> rejectedCommands() {

        return Stream.of(List.of("unknown", "--help"), List.of("runner", "--help"), List.of("help", "runner"));
    }

    @ParameterizedTest
    @MethodSource("rejectedCommands")
    void unknownAndRetiredCommandsRemainErrorsWithoutStartingAnything(List<String> args) throws Exception {
        Result result = launch(args, false);
        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output())
                .contains("Unknown command:")
                .doesNotContain("Starting SbaAgenticApplication", "HikariPool", "Tomcat");
        assertThat(result.database()).doesNotExist();
    }

    private Result launch(List<String> args, boolean invalidDatabase) throws Exception {
        Path fixture = Files.createDirectory(tempDir.resolve(UUID.randomUUID().toString()));
        Path home = Files.createDirectory(fixture.resolve("home"));
        Path db = fixture.resolve("fixture.db");
        Path output = fixture.resolve("output.log");
        // A server cannot bind this reserved port. Help must succeed without trying to listen.
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Duser.home=" + home,
                    "-Djava.io.tmpdir=" + fixture,
                    "-cp",
                    absoluteClasspath(),
                    SbaAgenticApplication.class.getName()));
            command.addAll(args);
            command.addAll(List.of(
                    "--spring.config.location=classpath:/application.yml",
                    "--spring.datasource.url=jdbc:sqlite:" + db,
                    "--server.address=127.0.0.1",
                    "--server.port=" + reserved.getLocalPort(),
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
                    "--sba.transcript.claude-roots[0]=" + home.resolve("unavailable"),
                    "--sba.transcript.codex-roots[0]=" + home.resolve("unavailable")));
            if (invalidDatabase) command.add("--spring.datasource.driver-class-name=fixture.MissingDriver");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(fixture.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment()
                    .putAll(Map.of("HOME", home.toString(), "TMPDIR", fixture.toString(), "LANG", "C.UTF-8"));
            Process process = builder.start();
            try {
                // Leave the pipe open with available data: ingest's readAllBytes would block here.
                process.getOutputStream()
                        .write("held fixture input\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                process.getOutputStream().flush();
                assertThat(process.waitFor(10, TimeUnit.SECONDS))
                        .as("Help exits while stdin remains open: " + args)
                        .isTrue();

                return new Result(process.exitValue(), Files.readString(output), db);
            } finally {
                process.getOutputStream().close();
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(5, TimeUnit.SECONDS))
                            .as("Owned fixture process stops")
                            .isTrue();
                }
            }
        }
    }

    private String absoluteClasspath() {

        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private record Result(int exitCode, String output, Path database) {}
}
