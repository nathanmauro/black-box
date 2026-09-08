package dev.nathan.sbaagentic.platform.internal.adapter.in.web.security;

import java.net.CookieManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP against the running application, with browser cookies and separate stateless agents. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-auth-test-${random.uuid}.db",
        "sba.local-ai.enabled=false", "sba.summary.backend=local", "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false", "server.shutdown=immediate",
        "SBA_AUTH_ENABLED=true", "SBA_AUTH_SECURE_COOKIES=false"
})
class AuthenticationHttpTest {
    private static final String PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
    @LocalServerPort int port;

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("SBA_AUTH_PASSWORD", () -> PASSWORD);
        registry.add("SBA_AUTH_API_TOKEN", () -> TOKEN);
    }

    @Test
    void browserLoginCaptureStreamAndLogoutRequireSessionAndCsrfWhileAgentsRemainStateless() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var browser = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
             var agent = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            assertUnauthorized(send(agent, get("/api/status")));
            assertUnauthorized(send(agent, get("/actuator/metrics")));
            assertUnauthorized(send(agent, get("/mcp")));
            assertUnauthorized(send(agent, postEvent("anonymous", null)));
            assertUnauthorized(send(agent, get("/api/stream")));
            for (String path : new String[]{"/", "/index.html", "/assets/private.js", "/sessions/example"}) {
                var response = send(agent, get(path).header("X-Forwarded-Host", "attacker.invalid")
                        .header("X-Forwarded-Proto", "https"));
                assertThat(response.statusCode()).isEqualTo(302);
                assertThat(response.headers().firstValue("Location")).contains("/login");
            }
            for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
                var response = send(agent, get(path));
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
            }
            assertUnauthorized(send(agent, get("/actuator/health/readiness/private")));

            var login = send(browser, get("/login"));
            assertThat(login.statusCode()).isEqualTo(200);
            String beforeLoginCsrf = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .findFirst().orElseThrow().getValue();
            var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
            assertThat(matcher.find()).isTrue();
            String form = "username=blackbox&password=" + encode(PASSWORD) + "&_csrf=" + encode(matcher.group(1));
            var signedIn = send(browser, get("/login").header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)));
            assertThat(signedIn.statusCode()).isEqualTo(302);
            assertThat(signedIn.headers().firstValue("Location")).contains("/");
            assertThat(signedIn.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("JSESSIONID=")))
                    .anyMatch(value -> value.contains("HttpOnly") && value.contains("SameSite=Lax"));
            assertThat(send(browser, get("/")).statusCode()).isEqualTo(200);
            assertThat(send(browser, get("/api/status")).statusCode()).isEqualTo(200);
            String csrf = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .findFirst().orElseThrow().getValue();
            assertThat(csrf).isNotEqualTo(beforeLoginCsrf);

            assertThat(send(browser, postEvent("missing-csrf", null)).statusCode()).isEqualTo(403);
            assertThat(send(browser, postEvent("wrong-csrf", "incorrect")).statusCode()).isEqualTo(403);
            assertThat(send(browser, postEvent("browser", csrf)).statusCode()).isEqualTo(200);
            for (String malformed : new String[]{"Bearer incorrect", "Basic incorrect", "Bearer", "invalid"}) {
                assertUnauthorized(send(browser, postEvent("must-not-write", csrf).header("Authorization", malformed)));
            }
            var bearerWrite = send(agent, postEvent("agent", null).header("Authorization", "Bearer " + TOKEN));
            assertThat(bearerWrite.statusCode()).isEqualTo(200);
            assertThat(bearerWrite.headers().allValues("Set-Cookie")).isEmpty();
            assertUnauthorized(send(agent, get("/api/status")));

            var mcp = send(agent, get("/mcp").header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"auth-test","version":"1"}}}
                            """)));
            assertThat(mcp.statusCode()).isEqualTo(200);
            assertThat(mcp.body()).contains("serverInfo");
            assertThat(mcp.headers().allValues("Set-Cookie")).isEmpty();

            var stream = browser.send(get("/api/stream").header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(stream.statusCode()).isEqualTo(200);
            try (var streamReader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                assertThat(streamReader.readLine()).isEqualTo(":connected");
                assertThat(streamReader.readLine()).isEmpty();

                var loggedOut = send(browser, get("/logout").header("X-XSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.noBody()));
                assertThat(loggedOut.statusCode()).isEqualTo(302);
                assertThat(loggedOut.headers().firstValue("Location")).contains("/login?logout");
                var reader = Executors.newSingleThreadExecutor(runnable -> {
                    var thread = new Thread(runnable, "auth-heartbeat-test-reader");
                    thread.setDaemon(true);
                    return thread;
                });
                try {
                    var closed = reader.submit(() -> {
                        String line;
                        while ((line = streamReader.readLine()) != null) {
                            // A comment already flushed before logout is harmless; private events are not.
                            assertThat(line).isIn("", ":heartbeat");
                        }
                        return true;
                    });
                    assertThat(closed.get(20, TimeUnit.SECONDS))
                            .as("logout closes an idle stream on its next heartbeat, without publishing an event").isTrue();
                } finally {
                    stream.body().close(); // release a blocked read before BufferedReader.close acquires its lock
                    reader.shutdownNow();
                }
                assertThat(send(agent, postEvent("after-logout-agent", null).header("Authorization", "Bearer " + TOKEN)).statusCode()).isEqualTo(200);
                assertUnauthorized(send(browser, get("/api/status")));
                assertUnauthorized(send(browser, postEvent("after-logout", csrf)));
                var events = send(agent, get("/api/events").header("Authorization", "Bearer " + TOKEN));
                assertThat(events.body()).contains("browser-capture", "agent-capture")
                        .doesNotContain("anonymous-capture", "missing-csrf-capture", "wrong-csrf-capture", "must-not-write-capture", "after-logout-capture");
            }
        }
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10)).GET();
    }

    private HttpRequest.Builder postEvent(String label, String csrf) {
        var request = get("/api/events").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"source":"auth-test","clientSessionId":"auth-test","eventType":"Observation","text":"%s-capture"}
                        """.formatted(label)));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        return request;
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"message\":\"Authentication required\"}");
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
