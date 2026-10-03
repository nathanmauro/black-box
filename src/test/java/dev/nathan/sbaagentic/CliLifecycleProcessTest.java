package dev.nathan.sbaagentic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.summary.SummaryBackfillResult;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

class CliLifecycleProcessTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    static Stream<List<String>> readCommands() {

        return Stream.of(
                List.of("sessions"), List.of("search", "fixture"), List.of("doctor"), List.of("embeddings-backfill"));
    }

    @ParameterizedTest
    @MethodSource("readCommands")
    void readCommandsExitAfterJson(List<String> args) throws Exception {
        Fixture fixture = fixture();
        Result result = run(fixture, args);
        assertThat(result.exitCode()).as(result.output()).isZero();
        JsonNode json = mapper.readTree(result.output());
        assertThat(json).isNotNull();
        assertThat(eventCount(fixture)).isZero();
        if (args.getFirst().equals("embeddings-backfill"))
            assertThat(json.path("apply").asBoolean()).isFalse();
        if (args.getFirst().equals("doctor")) {
            assertThat(json.path("localAi").path("enabled").asBoolean()).isFalse();
            assertThat(json.path("elasticsearch").path("enabled").asBoolean()).isFalse();
        }
    }

    @Test
    void captureCommitsBeforeSuccessfulExit() throws Exception {
        Fixture fixture = fixture();
        Result result = run(fixture, List.of("ingest", "--session=cli-fixture", "--text=Durable fixture"));
        assertThat(result.exitCode()).as(result.output()).isZero();
        JsonNode capture = mapper.readTree(result.output());
        assertThat(capture.path("eventId").asText()).isNotBlank();
        assertThat(eventCount(fixture)).isEqualTo(1);
        assertThat(scalar(fixture, "SELECT text FROM agent_events")).isEqualTo("Durable fixture");
        assertThat(scalar(fixture, "SELECT id FROM agent_events"))
                .isEqualTo(capture.path("eventId").asText());
    }

    @Test
    void commandFailureStillExitsNonzero() throws Exception {
        Fixture fixture = fixture();
        Result result = run(fixture, List.of("search"));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("search requires a query");
        assertThat(eventCount(fixture)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"summarize", "summarize-missing"})
    void explicitSummaryFinishesAndPersistsBeforeExit(String command) throws Exception {
        Fixture fixture = fixture();
        Result capture = run(fixture, List.of("ingest", "--session=summary-fixture", "--text=Local fixture evidence"));
        assertThat(capture.exitCode()).as(capture.output()).isZero();
        String session = mapper.readTree(capture.output()).path("sessionId").asText();
        Result result = run(fixture, command.equals("summarize") ? List.of(command, session) : List.of(command));
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(mapper.readTree(result.output())).isNotNull();
        assertThat(scalar(fixture, "SELECT summary FROM agent_sessions")).contains("Local fixture evidence");
        assertThat(eventCount(fixture)).isEqualTo(1);
    }

    @Test
    void terminalCaptureRemainsCommittedWhenOptionalSummaryIsPendingAtClose() throws Exception {
        Fixture fixture = fixture();
        Path factories =
                Files.createDirectories(fixture.directory().resolve("META-INF")).resolve("spring.factories");
        Files.writeString(
                factories,
                ApplicationContextInitializer.class.getName() + "=" + BlockingSummaryInitializer.class.getName());
        Result result = run(
                fixture,
                List.of(
                        "ingest",
                        "--session=terminal-fixture",
                        "--type=Stop",
                        "--text=Terminal fixture evidence",
                        "--fixture.summary.marker=" + fixture.directory().resolve("summary-started")));
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(mapper.readTree(result.output()).path("eventId").asText()).isNotBlank();
        assertThat(fixture.directory().resolve("summary-started")).exists();
        assertThat(eventCount(fixture)).isEqualTo(1);
        assertThat(scalar(fixture, "SELECT event_type FROM agent_events")).isEqualTo("Stop");
        assertThat(scalar(fixture, "SELECT text FROM agent_events")).isEqualTo("Terminal fixture evidence");
        assertThat(scalar(fixture, "SELECT summary FROM agent_sessions")).isNull();
    }

    @Test
    void noCommandServerRemainsAvailableUntilGracefulShutdown() throws Exception {
        Fixture fixture = fixture();
        Process process = start(fixture, List.of("--logging.level.root=INFO"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            var portPattern = Pattern.compile("Tomcat started on port (\\d+)");
            int port = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && process.isAlive()) {
                var match = portPattern.matcher(Files.readString(fixture.output()));
                if (match.find()) {
                    port = Integer.parseInt(match.group(1));
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(port).as(Files.readString(fixture.output())).isPositive();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(process.isAlive()).isTrue();
            process.destroy();
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("Graceful owned server shutdown")
                    .isTrue();
            assertThat(Files.readString(fixture.output())).contains("Graceful shutdown complete", "Shutdown completed");
        } finally {
            stop(process);
        }
    }

    private Result run(Fixture fixture, List<String> args) throws Exception {
        Process process = start(fixture, args);
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("Command exits after completing: " + args)
                    .isTrue();

            return new Result(process.exitValue(), Files.readString(fixture.output()));
        } finally {
            stop(process);
        }
    }

    private Process start(Fixture fixture, List<String> args) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + fixture.home(),
                "-Djava.io.tmpdir=" + fixture.directory(),
                "-cp",
                fixture.directory() + File.pathSeparator + classpath,
                SbaAgenticApplication.class.getName()));
        command.addAll(args);
        command.addAll(List.of(
                "--spring.config.location=classpath:/application.yml",
                "--spring.datasource.url=jdbc:sqlite:" + fixture.database(),
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
                "--sba.transcript.codex-roots[0]=" + fixture.home().resolve("unavailable"),
                "--spring.main.banner-mode=off"));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(fixture.directory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(fixture.output().toFile());
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
        process.getOutputStream().close();

        return process;
    }

    private void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS))
                    .as("Owned fixture process reaped")
                    .isTrue();
        }
    }

    private Fixture fixture() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve(UUID.randomUUID().toString()));

        return new Fixture(
                directory,
                Files.createDirectory(directory.resolve("home")),
                directory.resolve("fixture.db"),
                directory.resolve("output.log"));
    }

    private String scalar(Fixture fixture, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();

            return rows.getString(1);
        }
    }

    private int eventCount(Fixture fixture) throws Exception {

        return Integer.parseInt(scalar(fixture, "SELECT count(*) FROM agent_events"));
    }

    private record Fixture(Path directory, Path home, Path database, Path output) {}

    private record Result(int exitCode, String output) {}

    /** Activated only by this test's temporary spring.factories resource in its isolated child JVM. */
    public static class BlockingSummaryInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            CountDownLatch started = new CountDownLatch(1);
            Path marker = Path.of(context.getEnvironment().getRequiredProperty("fixture.summary.marker"));
            SummaryOperations fake = new SummaryOperations() {
                @Override
                public AgentSession summarize(String sessionId) {
                    try {
                        Files.writeString(marker, "optional summary began");
                        started.countDown();
                        new CountDownLatch(1).await();
                        throw new AssertionError("Blocking fixture unexpectedly completed");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Fixture summary interrupted during shutdown", ex);
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }

                @Override
                public AgentSession summarize(String source, String clientSessionId) {

                    return summarize(clientSessionId);
                }

                @Override
                public SummaryBackfillResult summarizeMissing(int limit) {
                    throw new UnsupportedOperationException();
                }
            };
            context.addBeanFactoryPostProcessor(
                    factory -> factory.registerResolvableDependency(SummaryOperations.class, fake));
            context.addApplicationListener(event -> {
                if (event instanceof ApplicationReadyEvent) {
                    try {
                        if (!started.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("Fixture summary never started");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
            });
        }
    }
}
