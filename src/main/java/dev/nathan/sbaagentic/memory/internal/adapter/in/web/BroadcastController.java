package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.ProjectBroadcast;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastClear;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastClearResult;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastListQuery;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastListResponse;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Per-project broadcasts over HTTP: list, add or refresh by key, and clear. */
@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

    private final BroadcastService broadcasts;

    public BroadcastController(BroadcastService broadcasts) {
        this.broadcasts = broadcasts;
    }

    @GetMapping
    public BroadcastListResponse list(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Boolean staleOnly,
            @RequestParam(required = false) Integer staleAfterHours,
            @RequestParam(required = false) Boolean allProjects,
            @RequestParam(required = false) Boolean includeCleared,
            @RequestParam(required = false) Integer limit) {
        BroadcastListQuery query =
                new BroadcastListQuery(project, kind, staleOnly, staleAfterHours, allProjects, includeCleared, limit);

        return broadcasts.list(query);
    }

    @PutMapping
    public ProjectBroadcast put(@RequestBody BroadcastRequest request) {

        return broadcasts.set(request.toCommand());
    }

    @PostMapping
    public ProjectBroadcast post(@RequestBody BroadcastRequest request) {

        return broadcasts.set(request.toCommand());
    }

    @PostMapping("/clear")
    public BroadcastClearResult clear(@RequestBody BroadcastClearRequest request) {

        return broadcasts.clear(request.toCommand());
    }

    @DeleteMapping
    public BroadcastClearResult delete(
            @RequestParam String repo,
            @RequestParam String key,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String clientSessionId) {

        return broadcasts.clear(new BroadcastClear(source, clientSessionId, repo, key, reason));
    }
}
