package dev.nathan.sbaagentic.memory.internal.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.RecallRequestContext;
import dev.nathan.sbaagentic.memory.internal.application.TextEmbeddingUnavailable;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class OllamaTextEmbedderClientTest {

    @Test
    void embedsQueryWithConfiguredPrefixFromSingularEmbeddingResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTextEmbedderClient client = new OllamaTextEmbedderClient(properties(), builder.build());
        server.expect(requestTo("http://embedding.test/api/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("X-Blackbox-Recall-Id"))
                .andExpect(content().json("""
                        {"model":"nomic-embed-text","prompt":"search_query: why do tests deadlock"}
                        """))
                .andRespond(withSuccess("""
                        {"embedding":[0.25,0.5,0.75]}
                        """, APPLICATION_JSON));

        assertThat(client.embedQuery("why do tests deadlock").values()).containsExactly(0.25f, 0.5f, 0.75f);
        assertThat(client.model()).isEqualTo("nomic-embed-text");
        assertThat(client.dimensions()).isEqualTo(3);
        server.verify();
    }

    @Test
    void embedsDocumentWithConfiguredPrefixFromNestedEmbeddingsResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTextEmbedderClient client = new OllamaTextEmbedderClient(properties(), builder.build());
        server.expect(requestTo("http://embedding.test/api/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"model":"nomic-embed-text","prompt":"search_document: semantic recall"}
                        """))
                .andRespond(withSuccess("""
                        {"embeddings":[[1.0,0.0,-1.0]]}
                        """, APPLICATION_JSON));

        assertThat(client.embedDocument("semantic recall").values()).containsExactly(1.0f, 0.0f, -1.0f);
        server.verify();
    }

    @Test
    void prefixesCanBeDisabled() {
        MemoryEmbeddingProperties properties = properties();
        properties.setQueryPrefix("");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTextEmbedderClient client = new OllamaTextEmbedderClient(properties, builder.build());
        server.expect(requestTo("http://embedding.test/api/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"model":"nomic-embed-text","prompt":"raw query"}
                        """))
                .andRespond(withSuccess("""
                        {"embedding":[1.0,0.0,0.0]}
                        """, APPLICATION_JSON));

        assertThat(client.embedQuery("raw query").values()).containsExactly(1.0f, 0.0f, 0.0f);
        server.verify();
    }

    @Test
    void documentContentHashIncludesDocumentPrefix() {
        MemoryEmbeddingProperties properties = properties();
        properties.setDocumentPrefix("doc:");
        OllamaTextEmbedderClient client =
                new OllamaTextEmbedderClient(properties, RestClient.builder().build());

        assertThat(client.documentContentHash("stored text"))
                .isEqualTo(EmbeddingVector.contentHash("doc:stored text"))
                .isNotEqualTo(EmbeddingVector.contentHash("stored text"));
    }

    @Test
    void dimensionMismatchFailsHard() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTextEmbedderClient client = new OllamaTextEmbedderClient(properties(), builder.build());
        server.expect(requestTo("http://embedding.test/api/embeddings")).andRespond(withSuccess("""
                        {"embedding":[1.0,2.0]}
                        """, APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedQuery("too short"))
                .isInstanceOf(TextEmbeddingUnavailable.class)
                .hasMessageContaining("dimensions 2")
                .hasMessageContaining("expected 3");
        server.verify();
    }

    @Test
    void disabledClientIsUnavailableAndDoesNotCallHttp() {
        MemoryEmbeddingProperties properties = properties();
        properties.setEnabled(false);
        OllamaTextEmbedderClient client =
                new OllamaTextEmbedderClient(properties, RestClient.builder().build());

        assertThat(client.available()).isFalse();
        assertThatThrownBy(() -> client.embedQuery("text"))
                .isInstanceOf(TextEmbeddingUnavailable.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void unreachableClientIsUnavailableAndEmbedThrowsTypedException() {
        OllamaTextEmbedderClient client = new OllamaTextEmbedderClient(
                properties(),
                RestClient.builder().requestFactory(new FailingRequestFactory()).build());

        assertThat(client.available()).isFalse();
        assertThatThrownBy(() -> client.embedDocument("text"))
                .isInstanceOf(TextEmbeddingUnavailable.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void availableChecksServerReachability() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTextEmbedderClient client = new OllamaTextEmbedderClient(properties(), builder.build());
        server.expect(requestTo("http://embedding.test/"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(client.available()).isTrue();
        server.verify();
    }

    @Test
    void recallCorrelationReachesBothAvailabilityAndEmbeddingHttpRequests() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://embedding.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTextEmbedderClient client = new OllamaTextEmbedderClient(properties(), builder.build());
        try (var context = RecallRequestContext.open("http", "codex", "test", null)) {
            server.expect(requestTo("http://embedding.test/"))
                    .andExpect(header("X-Blackbox-Recall-Id", context.requestId()))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));
            server.expect(requestTo("http://embedding.test/api/embeddings"))
                    .andExpect(header("X-Blackbox-Recall-Id", context.requestId()))
                    .andRespond(withSuccess("{\"embedding\":[1,0,0]}", APPLICATION_JSON));
            assertThat(client.available()).isTrue();
            client.embedQuery("private query");
        }
        server.verify();
    }

    private static MemoryEmbeddingProperties properties() {
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        properties.setDimensions(3);
        properties.setTimeout(Duration.ofMillis(50));

        return properties;
    }

    private static final class FailingRequestFactory implements ClientHttpRequestFactory {
        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            throw new ResourceAccessException("timed out");
        }
    }
}
