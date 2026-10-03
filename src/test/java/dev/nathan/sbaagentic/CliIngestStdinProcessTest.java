package dev.nathan.sbaagentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real CLI/storage input contracts; each owned child uses private storage and disabled providers. */
class CliIngestStdinProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void delayedProducerAndSplitUtf8AreCapturedOnlyAfterEof() throws Exception {
        try (Fixture fixture = launch(false)) {
            fixture.started();
            fixture.noAcknowledgement();
            byte[] text = "Delayed 🐈 capture\n".getBytes(StandardCharsets.UTF_8);
            // Split within the four-byte code point, then keep the complete input open before EOF.
            fixture.process().getOutputStream().write(text, 0, 10);
            fixture.process().getOutputStream().flush();
            fixture.noAcknowledgement();
            fixture.process().getOutputStream().write(text, 10, text.length - 10);
            fixture.process().getOutputStream().flush();
            fixture.noAcknowledgement();
            fixture.process().getOutputStream().close();
            fixture.acknowledged();
            fixture.assertStored("Delayed 🐈 capture\n");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Explicit 🐈 capture", "", " \n "})
    void explicitTextAcknowledgesWhileStdinRemainsOpen(String text) throws Exception {
        try (Fixture fixture = launch(false, "--text=" + text)) {
            fixture.acknowledged();
            fixture.assertStored(text.isBlank() ? null : text);
        }
    }

    @Test
    void emptyEofPreservesNoTextCapture() throws Exception {
        try (Fixture fixture = launch(false)) {
            fixture.process().getOutputStream().close();
            fixture.acknowledged();
            fixture.assertStored(null);
        }
    }

    @Test
    void acceptedByteLimitStillUsesCanonicalTextTruncation() throws Exception {
        try (Fixture fixture = launch(false)) {
            byte[] bytes = new byte[1024 * 1024];
            Arrays.fill(bytes, (byte) 'x');
            fixture.process().getOutputStream().write(bytes);
            fixture.process().getOutputStream().close();
            fixture.acknowledged();
            fixture.assertStored("x".repeat(20_000) + "\n[truncated]");
        }
    }

    @Test
    void oversizeInputIsRejectedBeforePersistenceWithoutWaitingForEof() throws Exception {
        try (Fixture fixture = launch(false)) {
            fixture.process().getOutputStream().write(new byte[1024 * 1024 + 1]);
            fixture.process().getOutputStream().flush();
            fixture.failed("stdin exceeds the 1048576-byte limit");
        }
    }

    @Test
    void malformedUtf8IsRejectedBeforePersistence() throws Exception {
        try (Fixture fixture = launch(false)) {
            fixture.process().getOutputStream().write(new byte[] {(byte) 0xc3, 0x28});
            fixture.process().getOutputStream().close();
            fixture.failed("MalformedInputException");
        }
    }

    @Test
    void valuelessTextIsRejectedWithoutReadingStdinOrSavingAnEvent() throws Exception {
        try (Fixture fixture = launch(false, "--text")) {
            fixture.failed("--text requires a value");
        }
    }

    @Test
    void stdinReadErrorIsRejectedBeforePersistence() throws Exception {
        try (Fixture fixture = launch(true)) {
            fixture.failed("synthetic stdin failure");
        }
    }

    private Fixture launch(boolean failingInput, String... options) throws IOException {
        Path fixture = Files.createDirectory(tempDir.resolve(UUID.randomUUID().toString()));
        Path home = Files.createDirectory(fixture.resolve("home"));
        Path db = fixture.resolve("fixture.db");
        Path output = fixture.resolve("output.log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "-Djava.io.tmpdir=" + fixture,
                "-cp",
                Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                        .map(path -> Path.of(path).toAbsolutePath().toString())
                        .collect(Collectors.joining(File.pathSeparator)),
                failingInput ? FailingInputMain.class.getName() : SbaAgenticApplication.class.getName(),
                "ingest",
                "--session=stdin-fixture"));
        command.addAll(List.of(options));
        command.addAll(List.of(
                "--spring.config.location=classpath:/application.yml",
                "--spring.datasource.url=jdbc:sqlite:" + db,
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
                "--sba.transcript.claude-roots[0]=" + home.resolve("unavailable"),
                "--sba.transcript.codex-roots[0]=" + home.resolve("unavailable"),
                "--logging.level.dev.nathan.sbaagentic.SbaAgenticApplication=INFO"));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(fixture.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("HOME", home.toString(), "TMPDIR", fixture.toString(), "LANG", "C.UTF-8"));

        return new Fixture(builder.start(), output, db);
    }

    public static class FailingInputMain {
        public static void main(String[] args) {
            System.setIn(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("synthetic stdin failure");
                }
            });
            SbaAgenticApplication.main(args);
        }
    }

    private record Fixture(Process process, Path output, Path db) implements AutoCloseable {
        void started() {
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () -> assertThat(Files.readString(output)).contains("Started SbaAgenticApplication"));
        }

        void noAcknowledgement() {
            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(Files.readString(output)).doesNotContain("\"eventId\""));
        }

        void acknowledged() {
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(Files.readString(output)).contains("\"eventId\""));
        }

        void failed(String message) throws Exception {
            assertThat(process.waitFor(15, TimeUnit.SECONDS))
                    .as(Files.readString(output))
                    .isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isNotZero();
            assertThat(Files.readString(output)).contains(message).doesNotContain("\"eventId\"");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                    var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM agent_events")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
        }

        void assertStored(String text) throws Exception {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                    var rows = connection.createStatement().executeQuery("SELECT text FROM agent_events")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(text);
                assertThat(rows.next()).isFalse();
            }
        }

        @Override
        public void close() throws Exception {
            try {
                process.getOutputStream().close();
            } finally {
                // Successful CLI exit is a separate change; always clean up this owned fixture.
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                    assertThat(process.waitFor(5, TimeUnit.SECONDS))
                            .as("Owned stdin fixture stops")
                            .isTrue();
                }
            }
        }
    }
}
