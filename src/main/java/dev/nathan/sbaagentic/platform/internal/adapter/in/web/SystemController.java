package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import java.util.Map;

import dev.nathan.sbaagentic.ai.LocalAiClient;
import dev.nathan.sbaagentic.recording.DashboardStats;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SystemController {

    private final RecordingCatalog repository;
    private final LocalAiClient localAiClient;
    private final MemorySearchOperations memorySearch;

    public SystemController(
            RecordingCatalog repository,
            LocalAiClient localAiClient,
            MemorySearchOperations memorySearch) {
        this.repository = repository;
        this.localAiClient = localAiClient;
        this.memorySearch = memorySearch;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "storage", repository.stats(),
                "localAi", localAiClient.health(),
                "elasticsearch", memorySearch.elasticHealth());
    }

    @GetMapping("/stats")
    public DashboardStats stats() {
        return repository.dashboardStats();
    }

    @GetMapping("/health/local-ai")
    public Object localAiHealth() {
        return localAiClient.health();
    }

    @GetMapping("/health/elasticsearch")
    public Object elasticsearchHealth() {
        return memorySearch.elasticHealth();
    }
}
