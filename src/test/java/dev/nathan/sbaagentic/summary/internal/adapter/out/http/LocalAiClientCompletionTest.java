package dev.nathan.sbaagentic.summary.internal.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nathan.sbaagentic.summary.ModelCompletion;
import dev.nathan.sbaagentic.summary.SummaryModelProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/** The finish-reason sibling of {@code complete()} over a real loopback socket; {@code complete()} is unchanged. */
class LocalAiClientCompletionTest {

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> reply = new AtomicReference<>();
    private HttpServer server;
    private SummaryModelProperties properties;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
        properties = new SummaryModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void returnsContentFinishReasonAndServedModelWithoutChangingComplete() {
        reply.set("""
                {"model":"served-model","choices":[{"index":0,"message":{"role":"assistant",\
                "content":"  Restated [E1].  "},"finish_reason":"length"}]}""");
        var client = new LocalAiClient(properties);

        assertThat(client.completeWithMetadata("system text", "user text", 77))
                .isEqualTo(new ModelCompletion("Restated [E1].", "length", "served-model"));
        assertThat(client.complete("system text", "user text", 77)).isEqualTo("Restated [E1].");
        assertThat(bodies)
                .hasSize(2)
                .allSatisfy(body -> assertThat(body)
                        .contains("\"max_tokens\":77")
                        .contains("\"stream\":false")
                        .contains("system text")
                        .contains("user text"));
    }

    @Test
    void anEmptyReplyIsReturnedForTheCallerToRejectWhileCompleteStillThrows() {
        reply.set("{\"choices\":[{\"message\":{\"content\":\"   \"},\"finish_reason\":\"stop\"}]}");
        var client = new LocalAiClient(properties);

        assertThat(client.completeWithMetadata("s", "u", 10)).isEqualTo(new ModelCompletion("", "stop", null));
        assertThatThrownBy(() -> client.complete("s", "u", 10)).isInstanceOf(RestClientException.class);
    }

    @Test
    void aRelativeChatPathResolvesAgainstTheBaseUrlIncludingItsPath() {
        String base = properties.getBaseUrl();

        assertThat(properties.chatUri()).isEqualTo(URI.create(base + "/v1/chat/completions"));
        properties.setBaseUrl(base + "/proxy");
        assertThat(properties.chatUri()).isEqualTo(URI.create(base + "/proxy/v1/chat/completions"));
    }

    /** The URI a caller can check is the one the client posts to, even when the chat path names its own host. */
    @Test
    void anAbsoluteChatPathSendsTheCallToTheHostItNames() throws IOException {
        List<String> elsewhere = new CopyOnWriteArrayList<>();
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/other/chat", exchange -> respond(exchange, elsewhere));
        other.start();
        try {
            reply.set("{\"choices\":[{\"message\":{\"content\":\"Moved [E1].\"},\"finish_reason\":\"stop\"}]}");
            URI target = URI.create("http://127.0.0.1:" + other.getAddress().getPort() + "/other/chat");
            for (String chatPath : List.of(target.toString(), "//127.0.0.1:" + target.getPort() + "/other/chat")) {
                properties.setChatPath(chatPath);

                assertThat(properties.chatUri()).isEqualTo(target);
                assertThat(new LocalAiClient(properties).completeWithMetadata("s", "u", 10))
                        .isEqualTo(new ModelCompletion("Moved [E1].", "stop", null));
            }
            assertThat(elsewhere).hasSize(2);
            assertThat(bodies).isEmpty();
        } finally {
            other.stop(0);
        }
    }

    @Test
    void aDisabledClientThrowsWithoutACall() {
        properties.setEnabled(false);
        var client = new LocalAiClient(properties);

        assertThatThrownBy(() -> client.completeWithMetadata("s", "u", 10)).isInstanceOf(RestClientException.class);
        assertThat(bodies).isEmpty();
    }

    private void respond(HttpExchange exchange) throws IOException {
        respond(exchange, bodies);
    }

    private void respond(HttpExchange exchange, List<String> sink) throws IOException {
        sink.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
