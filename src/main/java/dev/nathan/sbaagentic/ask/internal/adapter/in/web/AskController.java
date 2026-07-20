package dev.nathan.sbaagentic.ask.internal.adapter.in.web;

import dev.nathan.sbaagentic.ask.AskRequest;
import dev.nathan.sbaagentic.ask.AskResponse;
import dev.nathan.sbaagentic.ask.AskRetrieveResponse;
import dev.nathan.sbaagentic.ask.AskService;
import dev.nathan.sbaagentic.ask.AskStatus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @GetMapping("/ask/status")
    public AskStatus askStatus() {
        return askService.status();
    }

    @GetMapping("/ask/retrieve")
    public AskRetrieveResponse askRetrieve(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        return askService.retrieve(q, safeLimit(limit));
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        return askService.ask(request);
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 250));
    }
}
