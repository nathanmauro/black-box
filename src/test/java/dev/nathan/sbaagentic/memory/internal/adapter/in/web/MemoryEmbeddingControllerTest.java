package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillRequest;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillResult;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingOperations;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MemoryEmbeddingControllerTest {

    private final RecordingMemoryEmbeddingOperations operations = new RecordingMemoryEmbeddingOperations();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MemoryEmbeddingController(operations))
            .build();

    @Test
    void backfillBindsDefaultsAndQueryParameters() throws Exception {
        mockMvc.perform(post("/api/memory/embeddings/backfill"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.apply").value(false))
                .andExpect(jsonPath("$.batchSize").value(100))
                .andExpect(jsonPath("$.scanned").value(3))
                .andExpect(jsonPath("$.candidates").value(2))
                .andExpect(jsonPath("$.embedded").value(0));

        mockMvc.perform(post("/api/memory/embeddings/backfill")
                        .param("apply", "true")
                        .param("batchSize", "5")
                        .param("progressEvery", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apply").value(true))
                .andExpect(jsonPath("$.batchSize").value(5))
                .andExpect(jsonPath("$.embedded").value(2));

        assertThat(operations.requests())
                .containsExactly(
                        new MemoryEmbeddingBackfillRequest(false, 100, 250),
                        new MemoryEmbeddingBackfillRequest(true, 5, 2));
    }

    private static final class RecordingMemoryEmbeddingOperations implements MemoryEmbeddingOperations {

        private final List<MemoryEmbeddingBackfillRequest> requests = new ArrayList<>();

        @Override
        public MemoryEmbeddingBackfillResult backfillEmbeddings(MemoryEmbeddingBackfillRequest request) {
            requests.add(request);

            return new MemoryEmbeddingBackfillResult(
                    request.apply(), request.batchSize(), 3, 2, 1, request.apply() ? 2 : 0, 0, false);
        }

        List<MemoryEmbeddingBackfillRequest> requests() {

            return List.copyOf(requests);
        }
    }
}
