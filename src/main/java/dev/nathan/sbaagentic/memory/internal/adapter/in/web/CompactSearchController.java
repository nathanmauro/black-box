package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.CompactSearchOperations;
import dev.nathan.sbaagentic.memory.internal.application.CompactSearchJson;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CompactSearchController {
    private final CompactSearchOperations search;

    public CompactSearchController(CompactSearchOperations search) {
        this.search = search;
    }

    @GetMapping(value = "/api/search/compact", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> search(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer maxBytes,
            @RequestParam(required = false) String excludeSession,
            @RequestParam(required = false) Boolean groupSimilar) {
        var result = search.search(q, limit, maxBytes, excludeSession, groupSimilar);

        return ResponseEntity.status("ok".equals(result.status()) ? 200 : 400)
                .contentType(MediaType.APPLICATION_JSON)
                .body(CompactSearchJson.write(result));
    }
}
