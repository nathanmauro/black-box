package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingOperations;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.summary.SummaryModelOperations;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

@ResourceLock("System.in")
class SbaCliIngestValidationTest {
    private final EventRecorder recorder = mock(EventRecorder.class);
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final SbaCli cli = new SbaCli(
            recorder,
            mock(RecordingCatalog.class),
            mock(MemorySearchOperations.class),
            mock(MemoryEmbeddingOperations.class),
            mock(SummaryOperations.class),
            mock(SummaryModelOperations.class),
            mapper);

    @BeforeEach
    void output() {
        when(mapper.writerWithDefaultPrettyPrinter()).thenReturn(mock(ObjectWriter.class));
    }

    static Stream<Arguments> ambiguousArguments() {

        return Stream.of(
                Arguments.of(List.of("ingest", "important note"), "ingest does not accept positional arguments"),
                Arguments.of(
                        List.of("ingest", "--session", "intended-session", "--text=note"),
                        "ingest does not accept positional arguments"));
    }

    @ParameterizedTest
    @MethodSource("ambiguousArguments")
    void unexpectedPositionalsFailBeforeReadingOrRecording(List<String> args, String message) throws Exception {
        assertRejected(args.toArray(String[]::new), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "session", "type", "turn", "role", "cwd", "tool", "title", "text"})
    void knownValuedOptionsCannotSilentlyUseTheirDefault(String option) throws Exception {
        assertRejected(new String[] {"ingest", "--" + option}, "--" + option + " requires a value");
    }

    @Test
    void repeatedBareOptionCannotHideBehindAnEarlierValue() throws Exception {
        assertRejected(new String[] {"ingest", "--session=fixture", "--session"}, "--session requires a value");
    }

    static Stream<Arguments> blankRequiredOptions() {

        return Stream.of("source", "session", "type")
                .flatMap(name -> Stream.of("", " \t\n ").map(value -> Arguments.of(name, value)));
    }

    @ParameterizedTest
    @MethodSource("blankRequiredOptions")
    void requiredCaptureFieldsCannotBeBlank(String option, String value) throws Exception {
        assertRejected(new String[] {"ingest", "--" + option + "=" + value}, "--" + option + " must not be blank");
    }

    @Test
    void defaultsAndSpringConfigurationRemainAcceptedWithoutReadingStdin() throws Exception {
        EventIngestRequest request = capture("ingest", "--text=--help", "--spring.main.banner-mode=off", "--debug");
        assertThat(request.source()).isEqualTo("manual");
        assertThat(request.clientSessionId()).startsWith("manual-");
        assertThat(request.eventType()).isEqualTo("ManualCapture");
        assertThat(request.text()).isEqualTo("--help");
    }

    @Test
    void optionalEqualsValuesCanStillBeEmpty() throws Exception {
        EventIngestRequest request = capture(
                "ingest",
                "--source=manual",
                "--session=fixture",
                "--type=Observation",
                "--text=",
                "--turn=",
                "--role=",
                "--cwd=",
                "--tool=",
                "--title=");
        assertThat(request.text()).isEmpty();
        assertThat(request.turnId()).isEmpty();
        assertThat(request.role()).isEmpty();
        assertThat(request.cwd()).isEmpty();
        assertThat(request.toolName()).isEmpty();
        assertThat(request.metadata()).containsEntry("title", "");
    }

    private void assertRejected(String[] args, String message) throws Exception {
        withUnreadStdin(() -> assertThatThrownBy(() -> cli.run(new DefaultApplicationArguments(args)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message));
        verifyNoInteractions(recorder, mapper);
    }

    private EventIngestRequest capture(String... args) throws Exception {
        withUnreadStdin(() -> cli.run(new DefaultApplicationArguments(args)));
        ArgumentCaptor<EventIngestRequest> request = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(request.capture());

        return request.getValue();
    }

    private void withUnreadStdin(CheckedAction action) throws Exception {
        InputStream original = System.in;
        System.setIn(new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("invalid or explicit arguments must not read stdin");
            }
        });
        try {
            action.run();
        } finally {
            System.setIn(original);
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
