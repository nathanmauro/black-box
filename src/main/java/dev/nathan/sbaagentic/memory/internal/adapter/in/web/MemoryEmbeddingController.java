package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillRequest;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillResult;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingOperations;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory/embeddings")
public class MemoryEmbeddingController {

    private final MemoryEmbeddingOperations memoryEmbeddingOperations;

    public MemoryEmbeddingController(MemoryEmbeddingOperations memoryEmbeddingOperations) {
        this.memoryEmbeddingOperations = memoryEmbeddingOperations;
    }

    @PostMapping("/backfill")
    public MemoryEmbeddingBackfillResult backfill(
            @RequestParam(defaultValue = "false") boolean apply,
            @RequestParam(defaultValue = "100") int batchSize,
            @RequestParam(defaultValue = "250") int progressEvery) {
        return memoryEmbeddingOperations.backfillEmbeddings(
                new MemoryEmbeddingBackfillRequest(apply, batchSize, progressEvery));
    }
}
