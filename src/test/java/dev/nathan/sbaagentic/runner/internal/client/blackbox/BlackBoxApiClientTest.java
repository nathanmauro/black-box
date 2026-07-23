package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlackBoxApiClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void claimTaskParsesTaskChangeResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks/claim", exchange -> respondJson(exchange, """
                {
                  "snapshot": {
                    "task": {
                      "id": "12345678-abcd-4abc-8abc-1234567890ab",
                      "specId": "87654321-abcd-4abc-8abc-1234567890ab",
                      "projectKey": "/tmp/project",
                      "title": "Runner task",
                      "lane": "auto",
                      "status": "in_progress",
                      "priority": 10,
                      "createdBy": "test",
                      "claimedBy": "blackbox-runner",
                      "blockedReason": null,
                      "resultHandoffId": null,
                      "createdAt": "2026-07-15T12:00:00Z",
                      "updatedAt": "2026-07-15T12:01:00Z"
                    },
                    "spec": {
                      "id": "87654321-abcd-4abc-8abc-1234567890ab",
                      "projectKey": "/tmp/project",
                      "title": "Runner story",
                      "body": "# Goal",
                      "specRef": null,
                      "status": "active",
                      "createdBy": "test",
                      "createdAt": "2026-07-15T12:00:00Z",
                      "updatedAt": "2026-07-15T12:00:00Z"
                    }
                  },
                  "event": {
                    "id": "aaaaaaaa-abcd-4abc-8abc-1234567890ab",
                    "taskId": "12345678-abcd-4abc-8abc-1234567890ab",
                    "type": "task.claimed",
                    "actor": "blackbox-runner",
                    "fromStatus": "open",
                    "toStatus": "in_progress",
                    "detail": null,
                    "observedAt": "2026-07-15T12:01:00Z"
                  }
                }
                """));
        server.start();
        try {
            BlackBoxApiClient client = client(server);

            Optional<TaskChange> claimed = client.claimTask("auto", "blackbox-runner");

            assertThat(claimed).isPresent();
            assertThat(claimed.orElseThrow().snapshot().task().id())
                    .isEqualTo("12345678-abcd-4abc-8abc-1234567890ab");
            assertThat(claimed.orElseThrow().snapshot().task().status()).isEqualTo(TaskStatus.IN_PROGRESS);
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void claimTaskReturnsEmptyOnNoContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks/claim", exchange -> exchange.sendResponseHeaders(204, -1));
        server.start();
        try {
            assertThat(client(server).claimTask("gate", "blackbox-runner")).isEmpty();
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void listTasksCanFilterApprovalReconciliationByStatusAndLane() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/api/tasks", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respondJson(exchange, "[]");
        });
        server.start();
        try {
            assertThat(client(server).listTasks("done", "sdlc:review")).isEmpty();
            assertThat(query.get())
                    .isEqualTo("status=done&lane=sdlc%3Areview&limit=250&offset=0");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void listTasksByStatusAggregatesEveryPage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<TaskSnapshot> tasks = tasks(251, TaskStatus.DONE);
        List<String> queries = new ArrayList<>();
        server.createContext("/api/tasks", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            respondPage(exchange, tasks);
        });
        server.start();
        try {
            assertThat(client(server).listTasks("done"))
                    .extracting(snapshot -> snapshot.task().id())
                    .containsExactlyElementsOf(tasks.stream().map(snapshot -> snapshot.task().id()).toList());
            assertThat(queries).containsExactly(
                    "status=done&limit=250&offset=0",
                    "status=done&limit=250&offset=250");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void nullStatusLaneScanExcludesCancelledAndAggregatesEveryPage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<TaskSnapshot> tasks = tasks(251, TaskStatus.OPEN);
        List<String> queries = new ArrayList<>();
        server.createContext("/api/tasks", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            respondPage(exchange, tasks);
        });
        server.start();
        try {
            assertThat(client(server).listTasks(null, "auto")).hasSize(251);
            assertThat(queries).containsExactly(
                    "lane=auto&excludeStatus=cancelled&limit=250&offset=0",
                    "lane=auto&excludeStatus=cancelled&limit=250&offset=250");
        }
        finally {
            server.stop(0);
        }
    }

    private static BlackBoxApiClient client(HttpServer server) {
        return new BlackBoxApiClient(
                OBJECT_MAPPER, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static List<TaskSnapshot> tasks(int count, TaskStatus status) {
        Instant createdAt = Instant.parse("2026-07-15T12:00:00Z");
        TaskSpec spec = new TaskSpec(
                "spec-1",
                "/tmp/project",
                "Runner story",
                "# Goal",
                null,
                SpecStatus.ACTIVE,
                "test",
                createdAt,
                createdAt);
        List<TaskSnapshot> tasks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            tasks.add(new TaskSnapshot(new Task(
                    "task-" + index,
                    spec.id(),
                    spec.projectKey(),
                    "Runner task " + index,
                    "auto",
                    status,
                    0,
                    "test",
                    null,
                    null,
                    null,
                    createdAt,
                    createdAt), spec));
        }
        return tasks;
    }

    private static void respondPage(HttpExchange exchange, List<TaskSnapshot> tasks) throws IOException {
        int offset = queryInt(exchange, "offset");
        int fromIndex = Math.min(offset, tasks.size());
        int toIndex = Math.min(offset + 250, tasks.size());
        respondJson(exchange, OBJECT_MAPPER.writeValueAsString(tasks.subList(fromIndex, toIndex)));
    }

    private static int queryInt(HttpExchange exchange, String name) {
        for (String parameter : exchange.getRequestURI().getRawQuery().split("&")) {
            String prefix = name + "=";
            if (parameter.startsWith(prefix)) {
                return Integer.parseInt(parameter.substring(prefix.length()));
            }
        }
        throw new AssertionError("Missing query parameter: " + name);
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
