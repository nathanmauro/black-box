package dev.nathan.sbaagentic.search;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nathan.sbaagentic.config.SbaProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticIndexClientTest {

    @Test
    void searchUsesFuzzyRelevanceQuery() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sba-agentic-events/_search", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, """
                    {
                      "hits": {
                        "hits": [
                          {
                            "_id": "event-1",
                            "_score": 7.25,
                            "_source": {
                              "text": "elastic smoke test",
                              "title": "Elastic smoke",
                              "source": "manual"
                            }
                          }
                        ]
                      }
                    }
                    """);
        });
        server.start();
        try {
            SbaProperties properties = new SbaProperties();
            properties.getElasticsearch().setEnabled(true);
            properties.getElasticsearch().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getElasticsearch().setTimeout(Duration.ofSeconds(2));
            ElasticIndexClient client = new ElasticIndexClient(properties);

            List<Map<String, Object>> hits = client.search("elstic smook", 7);

            assertThat(requestBody.get())
                    .contains("\"fuzziness\":\"AUTO\"")
                    .contains("\"minimum_should_match\":1")
                    .contains("\"highlight\"")
                    .doesNotContain("\"sort\"");
            assertThat(hits).hasSize(1);
            assertThat(hits.getFirst().get("score")).isEqualTo(7.25);
        }
        finally {
            server.stop(0);
        }
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
