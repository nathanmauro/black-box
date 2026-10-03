package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.CompactPageRequest;
import dev.nathan.sbaagentic.memory.CompactSearchOperations;
import dev.nathan.sbaagentic.memory.CompactSearchPage;
import dev.nathan.sbaagentic.memory.CompactSearchResult;
import dev.nathan.sbaagentic.memory.internal.application.CompactSearchJson;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CompactSearchController {
    private static final String CANONICAL = "canonical";
    private static final List<String> CANONICAL_ONLY = List.of("term", "projectExact", "sessionId", "until", "before");
    private static final List<String> LEGACY_ONLY = List.of("q", "groupSimilar", "excludeSession");
    private final CompactSearchOperations search;

    public CompactSearchController(CompactSearchOperations search) {
        this.search = search;
    }

    @GetMapping(value = "/api/search/compact", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer maxBytes,
            @RequestParam(required = false) String excludeSession,
            @RequestParam(required = false) Boolean groupSimilar,
            @RequestParam(required = false) String mode,
            HttpServletRequest request)
            throws MissingServletRequestParameterException {
        Map<String, String[]> parameters = request.getParameterMap();
        if (mode == null) {
            if (CANONICAL_ONLY.stream().anyMatch(parameters::containsKey)) {

                return json(
                        400,
                        new CompactSearchResult(
                                "invalid_request",
                                null,
                                0,
                                List.of(),
                                Map.of(),
                                Map.of(),
                                false,
                                0,
                                maxBytes == null ? 24_000 : maxBytes,
                                List.of("term, projectExact, sessionId, until and before require mode=canonical.")));
            }
            // Same missing-parameter response as the former required binding.
            if (q == null) throw new MissingServletRequestParameterException("q", "String");
            var result = search.search(q, limit, maxBytes, excludeSession, groupSimilar);

            return json("ok".equals(result.status()) ? 200 : 400, result);
        }
        String rejected = null;
        if (!CANONICAL.equals(mode)) rejected = "mode must be canonical or absent.";
        else if (LEGACY_ONLY.stream().anyMatch(parameters::containsKey))
            rejected = "mode=canonical does not accept q, groupSimilar or excludeSession; use term values.";
        else if (List.of("projectExact", "sessionId", "until", "before", "limit", "maxBytes", "mode").stream()
                .anyMatch(name -> parameters.containsKey(name) && parameters.get(name).length > 1))
            rejected = "Scalar canonical parameters may appear at most once.";
        if (rejected != null) {

            return json(
                    400,
                    new CompactSearchPage(
                            "invalid_request",
                            CANONICAL,
                            0,
                            List.of(),
                            Map.of(),
                            false,
                            null,
                            false,
                            false,
                            limit == null ? 10 : limit,
                            maxBytes == null ? 24_000 : maxBytes,
                            List.of(rejected)));
        }
        // Raw values: Spring list binding would split a single term on commas.
        String[] terms = parameters.get("term");
        var page = search.searchPage(new CompactPageRequest(
                terms == null ? List.of() : List.of(terms),
                single(parameters, "projectExact"),
                single(parameters, "sessionId"),
                single(parameters, "until"),
                single(parameters, "before"),
                limit,
                maxBytes));

        return json("ok".equals(page.status()) ? 200 : 400, page);
    }

    private static String single(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);

        return values == null ? null : values[0];
    }

    private static ResponseEntity<String> json(int status, Object body) {

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(CompactSearchJson.write(body));
    }
}
