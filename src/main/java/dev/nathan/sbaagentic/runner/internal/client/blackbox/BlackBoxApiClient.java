package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BlackBoxApiClient {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8766";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;

    @Autowired
    public BlackBoxApiClient(ObjectMapper objectMapper) {
        this(objectMapper, configuredBaseUrl());
    }

    public String baseUrl() {
        return baseUrl;
    }

    public BlackBoxApiClient(ObjectMapper objectMapper, String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public Optional<TaskChange> claimTask(String lane, String agent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lane", lane);
        body.put("agent", agent);
        HttpResponse<String> response = sendJson("POST", "/api/tasks/claim", body);
        if (response.statusCode() == 204) {
            return Optional.empty();
        }
        requireSuccess("POST", "/api/tasks/claim", response);
        return Optional.of(read(response.body(), TaskChange.class, response.statusCode()));
    }

    public TaskChange enqueueTask(String specId, String title, String lane, int priority, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("specId", specId);
        body.put("title", title);
        body.put("lane", lane);
        body.put("priority", priority);
        body.put("actor", actor);
        return exchangeJson("POST", "/api/tasks", body, TaskChange.class);
    }

    public TaskChange updateTaskStatus(
            String taskId, String actor, String status, String blockedReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor);
        body.put("status", status);
        body.put("blockedReason", blockedReason);
        return exchangeJson("PATCH", "/api/tasks/" + pathSegment(taskId), body, TaskChange.class);
    }

    public TaskChange completeTask(
            String taskId,
            String actor,
            String source,
            String clientSessionId,
            String summary,
            List<String> openLoops,
            String nextAction) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor);
        body.put("source", source);
        body.put("clientSessionId", clientSessionId);
        body.put("summary", summary);
        body.put("openLoops", openLoops);
        body.put("nextAction", nextAction);
        return exchangeJson(
                "POST", "/api/tasks/" + pathSegment(taskId) + "/complete", body, TaskChange.class);
    }

    public TaskAnnotation annotate(
            String taskId, String actor, String kind, String text, Map<String, Object> dataJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor);
        body.put("kind", kind);
        body.put("text", text);
        body.put("dataJson", dataJson);
        return exchangeJson(
                "POST", "/api/tasks/" + pathSegment(taskId) + "/annotations", body, TaskAnnotation.class);
    }

    public List<TaskEvent> taskEvents(String taskId) {
        String path = "/api/tasks/" + pathSegment(taskId) + "/events";
        HttpResponse<String> response = send("GET", path, null);
        requireSuccess("GET", path, response);
        return read(response.body(), new TypeReference<>() { }, response.statusCode());
    }

    public TaskSpec getSpec(String specId) {
        return exchangeJson("GET", "/api/specs/" + pathSegment(specId), null, TaskSpec.class);
    }

    public List<TaskSnapshot> listTasks(String status) {
        // Always request the server's maximum row cap: the default of 100 silently hides
        // tasks on busy lanes (a live cleanup pass missed 11 tasks past the default cap).
        String path = "/api/tasks?limit=250";
        if (status != null && !status.isBlank()) {
            path += "&status=" + queryValue(status);
        }
        HttpResponse<String> response = send("GET", path, null);
        requireSuccess("GET", path, response);
        return read(response.body(), new TypeReference<>() { }, response.statusCode());
    }

    public List<TaskSnapshot> listTasks(String status, String lane) {
        StringBuilder path = new StringBuilder("/api/tasks?");
        if (status != null && !status.isBlank()) {
            path.append("status=").append(queryValue(status)).append('&');
        }
        if (lane != null && !lane.isBlank()) {
            path.append("lane=").append(queryValue(lane)).append('&');
        }
        path.append("limit=250");
        HttpResponse<String> response = send("GET", path.toString(), null);
        requireSuccess("GET", path.toString(), response);
        return read(response.body(), new TypeReference<>() { }, response.statusCode());
    }

    public SessionLink createSessionLink(
            String parentSessionId, String childSessionId, String linkType, String taskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentSessionId", parentSessionId);
        body.put("childSessionId", childSessionId);
        body.put("linkType", linkType);
        body.put("taskId", taskId);
        return exchangeJson("POST", "/api/session-links", body, SessionLink.class);
    }

    public IngestResponse postEvent(
            String source,
            String clientSessionId,
            String turnId,
            String eventType,
            String role,
            String text,
            String cwd,
            String toolName,
            Object toolInput,
            Object toolOutput,
            Map<String, Object> metadata,
            Instant observedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("clientSessionId", clientSessionId);
        body.put("turnId", turnId);
        body.put("eventType", eventType);
        body.put("role", role);
        body.put("text", text);
        body.put("cwd", cwd);
        body.put("toolName", toolName);
        body.put("toolInput", toolInput);
        body.put("toolOutput", toolOutput);
        body.put("metadata", metadata);
        body.put("observedAt", observedAt);
        return exchangeJson("POST", "/api/events", body, IngestResponse.class);
    }

    public InputStream openEventStream() {
        String path = "/api/stream";
        HttpRequest request = request(path)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream input = response.body()) {
                    String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    throw new BlackBoxApiException("GET", uri(path).toString(), response.statusCode(), body);
                }
            }
            return response.body();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw transportFailure("GET", path, ex);
        }
        catch (IOException ex) {
            throw transportFailure("GET", path, ex);
        }
    }

    private <T> T exchangeJson(String method, String path, Object body, Class<T> responseType) {
        HttpResponse<String> response = send(method, path, body);
        requireSuccess(method, path, response);
        return read(response.body(), responseType, response.statusCode());
    }

    private HttpResponse<String> sendJson(String method, String path, Object body) {
        return send(method, path, body);
    }

    private HttpResponse<String> send(String method, String path, Object body) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8);
        HttpRequest.Builder builder = request(path).method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw transportFailure(method, path, ex);
        }
        catch (IOException ex) {
            throw transportFailure(method, path, ex);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(REQUEST_TIMEOUT);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private void requireSuccess(String method, String path, HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BlackBoxApiException(method, uri(path).toString(), response.statusCode(), response.body());
        }
    }

    private <T> T read(String body, Class<T> responseType, int statusCode) {
        try {
            return objectMapper.readValue(body, responseType);
        }
        catch (JsonProcessingException ex) {
            throw invalidJson(statusCode, body, ex);
        }
    }

    private <T> T read(String body, TypeReference<T> responseType, int statusCode) {
        try {
            return objectMapper.readValue(body, responseType);
        }
        catch (JsonProcessingException ex) {
            throw invalidJson(statusCode, body, ex);
        }
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        }
        catch (JsonProcessingException ex) {
            throw new BlackBoxApiException("Unable to serialize Black Box API request: " + ex.getMessage(), ex);
        }
    }

    private BlackBoxApiException invalidJson(int statusCode, String body, JsonProcessingException cause) {
        return new BlackBoxApiException(
                "Black Box API returned invalid JSON with HTTP " + statusCode + ": " + body, cause);
    }

    private BlackBoxApiException transportFailure(String method, String path, Exception cause) {
        return new BlackBoxApiException(
                method + " " + uri(path) + " failed: " + cause.getMessage(), cause);
    }

    private static String configuredBaseUrl() {
        String configured = System.getenv("SBA_BASE_URL");
        return configured == null || configured.isBlank() ? DEFAULT_BASE_URL : configured.strip();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String normalized = value.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
