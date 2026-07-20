package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.search.SearchService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam String q, @RequestParam(defaultValue = "25") int limit) {
        return searchService.search(q, safeLimit(limit));
    }

    @GetMapping("/search/fields")
    public List<Map<String, Object>> searchFields() {
        return searchService.fields();
    }

    @GetMapping("/search/values")
    public List<String> searchValues(
            @RequestParam String field,
            @RequestParam(required = false, defaultValue = "") String prefix,
            @RequestParam(defaultValue = "20") int limit) {
        return searchService.fieldValues(field, prefix, safeValueLimit(limit));
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 250));
    }

    private static int safeValueLimit(int limit) {
        return Math.max(1, Math.min(limit, 50));
    }
}
