package dev.nathan.sbaagentic.search;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.session.AgentSession;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticIndexClientTest {

    @Test
    void indexCreationUsesZeroReplicasForLocalSingleNodeDefault() throws Exception {
        AtomicReference<String> createBody = new AtomicReference<>();
        AtomicReference<String> documentBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sba-agentic-events", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                createBody.set(readBody(exchange));
                respondJson(exchange, "{\"acknowledged\":true}");
                return;
            }
            exchange.sendResponseHeaders(405, -1);
        });
        server.createContext("/sba-agentic-events/_doc/event-1", exchange -> {
            documentBody.set(readBody(exchange));
            respondJson(exchange, "{\"result\":\"created\"}");
        });
        server.start();
        try {
            ElasticIndexClient client = client(server, true);

            boolean indexed = client.index(session(), event());

            assertThat(indexed).isTrue();
            assertThat(createBody.get())
                    .contains("\"settings\"")
                    .contains("\"number_of_replicas\":0");
            assertThat(documentBody.get()).contains("\"text\":\"elastic smoke test note\"");
        }
        finally {
            server.stop(0);
        }
    }

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

    /**
     * {@code _field_caps} parsing: a canned {@code _field_caps} response with a keyword field
     * ({@code source}: searchable + aggregatable) and an analyzed text field ({@code text}:
     * searchable, not aggregatable) must parse into the {@code {name,type,searchable,aggregatable}}
     * shape that {@code FieldInfo} consumes, choosing the first capability entry per field and
     * skipping internal {@code _}-prefixed fields.
     */
    @Test
    void fieldCapsParsesNameTypeSearchableAggregatable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sba-agentic-events/_field_caps", exchange ->
                respondJson(exchange, """
                        {
                          "indices": ["sba-agentic-events"],
                          "fields": {
                            "source": {
                              "keyword": { "type": "keyword", "searchable": true, "aggregatable": true }
                            },
                            "text": {
                              "text": { "type": "text", "searchable": true, "aggregatable": false }
                            },
                            "_id": {
                              "_id": { "type": "_id", "searchable": true, "aggregatable": false }
                            }
                          }
                        }
                        """));
        server.start();
        try {
            ElasticIndexClient client = client(server, true);

            List<Map<String, Object>> caps = client.fieldCaps();

            // Internal _-prefixed fields are skipped, so only source and text survive.
            assertThat(caps).hasSize(2);
            Map<String, Object> source = capFor(caps, "source");
            assertThat(source.get("type")).isEqualTo("keyword");
            assertThat(source.get("searchable")).isEqualTo(true);
            assertThat(source.get("aggregatable")).isEqualTo(true);
            Map<String, Object> text = capFor(caps, "text");
            assertThat(text.get("type")).isEqualTo("text");
            assertThat(text.get("searchable")).isEqualTo(true);
            assertThat(text.get("aggregatable")).isEqualTo(false);

            // The parsed maps are exactly what FieldInfo.fromMap consumes into FieldCap{...}.
            SearchService.FieldInfo info = SearchService.FieldInfo.fromMap(source);
            assertThat(info.name()).isEqualTo("source");
            assertThat(info.type()).isEqualTo("keyword");
            assertThat(info.searchable()).isTrue();
            assertThat(info.aggregatable()).isTrue();
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * {@code _terms_enum} body construction + {@code terms[]} extraction against the canonical
     * example from the Elasticsearch docs (n=74): {@code POST /{index}/_terms_enum} with body
     * {@code {field, string, size, case_insensitive}} returning {@code {"terms":["kibana"],
     * "complete":true}}. The client must send those four body fields and read the top-level
     * {@code terms} array, ignoring the {@code complete} flag.
     */
    @Test
    void termsEnumBuildsBodyAndExtractsTerms() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sba-agentic-events/_terms_enum", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, """
                    {"terms":["kibana"],"complete":true}
                    """);
        });
        server.start();
        try {
            ElasticIndexClient client = client(server, true);

            List<String> terms = client.termsEnum("source", "kiba", 10);

            assertThat(requestBody.get())
                    .contains("\"field\":\"source\"")
                    .contains("\"string\":\"kiba\"")
                    .contains("\"size\":10")
                    .contains("\"case_insensitive\":false");
            assertThat(terms).containsExactly("kibana");
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * {@code _terms_enum} is unsupported on analyzed text fields (it returns {@code []} for them), so
     * the client short-circuits {@code title}/{@code text} to an empty list WITHOUT issuing any HTTP
     * call — the keyword-only whitelist guards the request.
     */
    @Test
    void termsEnumReturnsEmptyForTextFieldsWithoutCallingElasticsearch() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sba-agentic-events/_terms_enum", exchange -> {
            called.set(true);
            respondJson(exchange, """
                    {"terms":["should-not-be-returned"],"complete":true}
                    """);
        });
        server.start();
        try {
            ElasticIndexClient client = client(server, true);

            assertThat(client.termsEnum("title", "El", 10)).isEmpty();
            assertThat(client.termsEnum("text", "sm", 10)).isEmpty();
            assertThat(called.get()).isFalse();
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * When Elasticsearch is disabled, {@code _terms_enum} is never attempted (even for an otherwise
     * supported keyword field), so the caller falls back to the SQLite {@code DISTINCT} path.
     */
    @Test
    void termsEnumReturnsEmptyWhenElasticsearchDisabled() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sba-agentic-events/_terms_enum", exchange -> {
            called.set(true);
            respondJson(exchange, """
                    {"terms":["unreachable"],"complete":true}
                    """);
        });
        server.start();
        try {
            ElasticIndexClient client = client(server, false);

            assertThat(client.termsEnum("source", "cl", 10)).isEmpty();
            assertThat(called.get()).isFalse();
        }
        finally {
            server.stop(0);
        }
    }

    private static ElasticIndexClient client(HttpServer server, boolean enabled) {
        SbaProperties properties = new SbaProperties();
        properties.getElasticsearch().setEnabled(enabled);
        properties.getElasticsearch().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getElasticsearch().setTimeout(Duration.ofSeconds(2));
        return new ElasticIndexClient(properties);
    }

    private static Map<String, Object> capFor(List<Map<String, Object>> caps, String name) {
        return caps.stream()
                .filter(cap -> name.equals(cap.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field cap named " + name));
    }

    private static AgentSession session() {
        Instant observedAt = Instant.parse("2026-06-07T19:00:00Z");
        return new AgentSession(
                "session-1",
                "manual",
                "client-session-1",
                "Elastic smoke",
                "/tmp/sba-agentic",
                null,
                observedAt,
                observedAt,
                1);
    }

    private static AgentEvent event() {
        return new AgentEvent(
                "event-1",
                "session-1",
                "manual",
                "client-session-1",
                "turn-1",
                "ManualCapture",
                "user",
                "elastic smoke test note",
                null,
                null,
                null,
                Map.of("title", "Elastic smoke"),
                Instant.parse("2026-06-07T19:00:00Z"));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
