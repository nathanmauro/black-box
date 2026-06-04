package dev.nathan.sbaagentic.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.ai.LocalAiClient;
import dev.nathan.sbaagentic.ai.SessionSummaryService;
import dev.nathan.sbaagentic.event.EventIngestRequest;
import dev.nathan.sbaagentic.event.EventIngestService;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.search.ElasticIndexClient;
import dev.nathan.sbaagentic.search.SearchService;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SbaCli implements ApplicationRunner {

    private final EventIngestService ingestService;
    private final EventRepository repository;
    private final SearchService searchService;
    private final SessionSummaryService summaryService;
    private final LocalAiClient localAiClient;
    private final ElasticIndexClient elasticIndexClient;
    private final ObjectMapper objectMapper;

    public SbaCli(
            EventIngestService ingestService,
            EventRepository repository,
            SearchService searchService,
            SessionSummaryService summaryService,
            LocalAiClient localAiClient,
            ElasticIndexClient elasticIndexClient,
            ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.repository = repository;
        this.searchService = searchService;
        this.summaryService = summaryService;
        this.localAiClient = localAiClient;
        this.elasticIndexClient = elasticIndexClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> positional = args.getNonOptionArgs();
        if (positional.isEmpty()) {
            return;
        }

        switch (positional.getFirst()) {
            case "doctor" -> doctor();
            case "sessions" -> sessions(args);
            case "search" -> search(args, positional);
            case "ingest" -> ingest(args);
            case "summarize" -> summarize(positional);
            case "summarize-missing" -> summarizeMissing(args);
            default -> usage();
        }
    }

    private void doctor() throws IOException {
        writeJson(Map.of(
                "storage", repository.stats(),
                "localAi", localAiClient.health(),
                "elasticsearch", elasticIndexClient.health()));
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
        String text = option(args, "text", null);
        if ((text == null || text.isBlank()) && System.in.available() > 0) {
            text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    private void summarize(List<String> positional) throws IOException {
        if (positional.size() < 2) {
            throw new IllegalArgumentException("summarize requires a session id");
        }
        writeJson(summaryService.summarize(positional.get(1)));
    }

    private void summarizeMissing(ApplicationArguments args) throws IOException {
        writeJson(summaryService.summarizeMissing(limit(args, 10)));
    }

    private void usage() {
        System.out.println("""
                Usage:
                  sba-agentic doctor
                  sba-agentic sessions [--limit=25]
                  sba-agentic search <query> [--limit=25]
                  sba-agentic ingest --source=manual --session=my-session --type=ManualCapture --text='note'
                  sba-agentic summarize <session-id>
                  sba-agentic summarize-missing [--limit=10]
                """);
    }

    private void writeJson(Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, value);
        System.out.println();
    }

    private static int limit(ApplicationArguments args, int defaultValue) {
        String value = option(args, "limit", Integer.toString(defaultValue));
        return Math.max(1, Math.min(Integer.parseInt(value), 250));
    }

    private static String option(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.getFirst();
    }
}
