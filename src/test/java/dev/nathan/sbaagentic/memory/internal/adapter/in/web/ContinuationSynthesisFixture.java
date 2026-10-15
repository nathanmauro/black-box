package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/** A loopback OpenAI-compatible fake that records every request body, plus continuation seeding helpers. */
final class ContinuationSynthesisFixture {

    record Reply(int status, String body, long delayMillis) {}

    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(500, "{}", 0));
    private final HttpServer server;

    ContinuationSynthesisFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.add(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + "\n"
                            + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply current = reply.get();
            try {
                Thread.sleep(current.delayMillis());
                byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(current.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client gave up (timeout); nothing is left to answer.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {

        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    static String completion(String content, String finishReason) {

        return "{\"model\":\"served-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + quote(content) + "},\"finish_reason\":" + (finishReason == null ? "null" : quote(finishReason))
                + "}]}";
    }

    static String quote(String value) {

        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Seeds one handoff with a next action and returns its project path. */
    static String project(MockMvc http, ObjectMapper mapper, String nextAction) throws Exception {
        String project = "/fixture/continuation-synthesis-" + UUID.randomUUID();
        Map<String, Object> handoff = new LinkedHashMap<>();
        handoff.put("source", "codex");
        handoff.put("clientSessionId", "synthesis-" + UUID.randomUUID());
        handoff.put("repo", project);
        handoff.put("contextSummary", "Storage replaced; recovery unverified.");
        handoff.put("openLoops", List.of("Measure restore time"));
        handoff.put("nextAction", nextAction);
        http.perform(post("/api/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(handoff)))
                .andExpect(status().isOk());

        return project;
    }

    static JsonNode continuations(MockMvc http, ObjectMapper mapper, String project) throws Exception {

        return mapper.readTree(http.perform(get("/api/continuations").param("project", project))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    static JsonNode synthesize(MockMvc http, ObjectMapper mapper, Map<String, Object> body, ResultMatcher expected)
            throws Exception {

        return mapper.readTree(http.perform(post("/api/continuations/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString());
    }
}
