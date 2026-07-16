package dev.nathan.sbaagentic.runner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlackBoxApiClientTest {

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

    private static BlackBoxApiClient client(HttpServer server) {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new BlackBoxApiClient(
                objectMapper, "http://127.0.0.1:" + server.getAddress().getPort());
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
