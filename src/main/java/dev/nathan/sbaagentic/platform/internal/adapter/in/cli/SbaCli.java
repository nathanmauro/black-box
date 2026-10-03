package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillRequest;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingOperations;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.platform.CliCommands;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.summary.SummaryModelOperations;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SbaCli implements ApplicationRunner {

    static final int MAX_STDIN_BYTES = 1024 * 1024;

    private final EventRecorder ingestService;
    private final RecordingCatalog repository;
    private final MemorySearchOperations searchService;
    private final MemoryEmbeddingOperations memoryEmbeddingOperations;
    private final SummaryOperations summaryService;
    private final SummaryModelOperations localAiClient;
    private final ObjectMapper objectMapper;

    public SbaCli(
            EventRecorder ingestService,
            RecordingCatalog repository,
            MemorySearchOperations searchService,
            MemoryEmbeddingOperations memoryEmbeddingOperations,
            SummaryOperations summaryService,
            SummaryModelOperations localAiClient,
            ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.repository = repository;
        this.searchService = searchService;
        this.memoryEmbeddingOperations = memoryEmbeddingOperations;
        this.summaryService = summaryService;
        this.localAiClient = localAiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String help = CliCommands.helpFor(args.getSourceArgs());
        if (help != null) {
            System.out.print(help);

            return;
        }
        List<String> positional = args.getNonOptionArgs();
        if (positional.isEmpty()) {

            return;
        }

        switch (positional.getFirst()) {
            case "doctor" -> doctor();
            case "sessions" -> sessions(args);
            case "search" -> search(args, positional);
            case "ingest" -> ingest(args);
            case "embeddings-backfill" -> embeddingsBackfill(args);
            case "summarize" -> summarize(positional);
            case "summarize-missing" -> summarizeMissing(args);
            default -> usage();
        }
    }

    private void doctor() throws IOException {
        writeJson(Map.of(
                "storage", repository.stats(),
                "localAi", localAiClient.health(),
                "elasticsearch", searchService.elasticHealth()));
    }

    private void sessions(ApplicationArguments args) throws IOException {
        writeJson(repository.recentSessions(limit(args, 25)));
    }

    private void search(ApplicationArguments args, List<String> positional) throws IOException {
        String query = option(args, "q", null);
        if (query == null && positional.size() > 1) {
            query = String.join(" ", positional.subList(1, positional.size()));
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("search requires a query");
        }
        writeJson(searchService.search(query, limit(args, 25)));
    }

    private void ingest(ApplicationArguments args) throws IOException {
        String text = ingestText(args, System.in, System.console() != null);
        EventIngestRequest request = new EventIngestRequest(
                option(args, "source", "manual"),
                option(args, "session", "manual-" + Instant.now()),
                option(args, "turn", null),
                option(args, "type", "ManualCapture"),
                option(args, "role", "user"),
                text,
                option(args, "cwd", System.getProperty("user.dir")),
                option(args, "tool", null),
                null,
                null,
                Map.of("title", option(args, "title", "Manual capture")),
                Instant.now());
        writeJson(ingestService.ingest(request));
    }

    static String ingestText(ApplicationArguments args, InputStream input, boolean consoleAttached) throws IOException {
        if (args.containsOption("text")) {
            List<String> values = args.getOptionValues("text");
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("--text requires a value; use --text= for an empty capture");
            }

            return values.getFirst();
        }
        if (consoleAttached) {

            return null;
        }
        // A quiet open pipe is not EOF. Bound allocation while waiting for the producer to finish.
        byte[] bytes = input.readNBytes(MAX_STDIN_BYTES + 1);
        if (bytes.length > MAX_STDIN_BYTES) {
            throw new IllegalArgumentException("stdin exceeds the 1048576-byte limit");
        }

        // The decoder reports malformed UTF-8 instead of silently replacing captured text.
        return StandardCharsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private void summarize(List<String> positional) throws IOException {
        if (positional.size() < 2) {
            throw new IllegalArgumentException("summarize requires a session id");
        }
        writeJson(summaryService.summarize(positional.get(1)));
    }

    private void summarizeMissing(ApplicationArguments args) throws IOException {
        writeJson(summaryService.summarizeMissing(limit(args, 10)));
    }

    private void embeddingsBackfill(ApplicationArguments args) throws IOException {
        writeJson(memoryEmbeddingOperations.backfillEmbeddings(new MemoryEmbeddingBackfillRequest(
                flag(args, "apply"), optionInt(args, "batch-size", 100), optionInt(args, "progress-every", 250))));
    }

    private void usage() {
        System.out.print(CliCommands.usage());
    }

    private void writeJson(Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, value);
        System.out.println();
    }

    private static int limit(ApplicationArguments args, int defaultValue) {
        String value = option(args, "limit", Integer.toString(defaultValue));

        return Math.max(1, Math.min(Integer.parseInt(value), 250));
    }

    private static int optionInt(ApplicationArguments args, String name, int defaultValue) {

        return Integer.parseInt(option(args, name, Integer.toString(defaultValue)));
    }

    private static boolean flag(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {

            return false;
        }
        if (values.isEmpty()) {

            return true;
        }

        return Boolean.parseBoolean(values.getFirst());
    }

    private static String option(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {

            return defaultValue;
        }

        return values.getFirst();
    }
}
