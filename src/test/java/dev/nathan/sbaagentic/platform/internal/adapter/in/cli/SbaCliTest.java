package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.nathan.sbaagentic.memory.ElasticHealth;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingOperations;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.platform.CliCommands;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.StorageStats;
import dev.nathan.sbaagentic.summary.AiHealth;
import dev.nathan.sbaagentic.summary.SummaryModelOperations;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

class SbaCliTest {
    private final EventRecorder recorder = mock(EventRecorder.class);
    private final RecordingCatalog catalog = mock(RecordingCatalog.class);
    private final MemorySearchOperations search = mock(MemorySearchOperations.class);
    private final MemoryEmbeddingOperations embeddings = mock(MemoryEmbeddingOperations.class);
    private final SummaryOperations summaries = mock(SummaryOperations.class);
    private final SummaryModelOperations model = mock(SummaryModelOperations.class);
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final SbaCli cli = new SbaCli(recorder, catalog, search, embeddings, summaries, model, mapper);

    // init reads ~/.claude and ~/.codex; point it here so the test never depends on the real home.
    @TempDir
    Path tempHome;

    @BeforeEach
    void output() throws Exception {
        ObjectWriter writer = mock(ObjectWriter.class);
        when(writer.writeValueAsString(any())).thenReturn("{}");
        when(mapper.writerWithDefaultPrettyPrinter()).thenReturn(writer);
    }

    static Stream<String> commands() {

        return CliCommands.names().stream();
    }

    @ParameterizedTest
    @MethodSource("commands")
    void everyRegisteredCommandStillDispatchesToItsExistingOperation(String command) throws Exception {
        switch (command) {
            case "doctor" -> {
                when(catalog.stats()).thenReturn(new StorageStats(0, 0));
                when(model.health()).thenReturn(new AiHealth(false, false, "fixture", "disabled"));
                when(search.elasticHealth()).thenReturn(new ElasticHealth(false, false, "fixture", "disabled"));
                cli.run(new DefaultApplicationArguments(command));
                verify(catalog).stats();
                verify(model).health();
                verify(search).elasticHealth();
            }
            case "sessions" -> {
                cli.run(new DefaultApplicationArguments(command));
                verify(catalog).recentSessions(25);
            }
            case "search" -> {
                cli.run(new DefaultApplicationArguments(command, "fixture"));
                verify(search).search("fixture", 25);
            }
            case "ingest" -> {
                cli.run(new DefaultApplicationArguments(command, "--text=fixture"));
                verify(recorder).ingest(any(EventIngestRequest.class));
            }
            case "embeddings-backfill" -> {
                cli.run(new DefaultApplicationArguments(command));
                verify(embeddings).backfillEmbeddings(any());
            }
            case "summarize" -> {
                cli.run(new DefaultApplicationArguments(command, "fixture-session"));
                verify(summaries).summarize("fixture-session");
            }
            case "summarize-missing" -> {
                cli.run(new DefaultApplicationArguments(command));
                verify(summaries).summarizeMissing(10);
            }
            case "init" -> {
                cli.run(new DefaultApplicationArguments(
                        command, "--home=" + tempHome, "--repo-root=" + tempHome));
                // The planner renders each target's before/after JSON, then the report.
                verify(mapper, atLeastOnce()).writerWithDefaultPrettyPrinter();
            }
            case "export-adr" -> {
                cli.run(new DefaultApplicationArguments(command));
                verify(mapper).writerWithDefaultPrettyPrinter();
            }
            default -> throw new AssertionError("Missing dispatch contract for " + command);
        }
    }

    @ParameterizedTest
    @MethodSource("commands")
    void runnerHelpNeverInvokesTheCommandEvenWhenCalledWithoutMain(String command) throws Exception {
        cli.run(new DefaultApplicationArguments(command, "--help"));
        verifyNoInteractions(recorder, catalog, search, embeddings, summaries, model, mapper);
    }

    @Test
    void literalHelpTextAndSearchQueriesAreData() throws Exception {
        cli.run(new DefaultApplicationArguments("ingest", "--text=--help"));
        ArgumentCaptor<EventIngestRequest> request = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(recorder).ingest(request.capture());
        assertThat(request.getValue().text()).isEqualTo("--help");
        cli.run(new DefaultApplicationArguments("search", "--q=--help"));
        verify(search).search("--help", 25);
        cli.run(new DefaultApplicationArguments("search", "help"));
        verify(search).search("help", 25);
    }
}
