package dev.nathan.sbaagentic.platform.internal.adapter.in.web.security;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Models the proxy's internal HTTP connection while checking the external browser cookie flags. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-auth-secure-test-${random.uuid}.db",
        "sba.local-ai.enabled=false", "sba.summary.backend=local", "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false", "server.shutdown=immediate", "SBA_AUTH_ENABLED=true"
})
class SecureAuthenticationCookiesHttpTest {
    private static final String PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    @LocalServerPort int port;

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("SBA_AUTH_PASSWORD", () -> PASSWORD);
        registry.add("SBA_AUTH_API_TOKEN", () -> UUID.randomUUID().toString() + UUID.randomUUID());
    }

    @Test
    void productionCookieDefaultsRemainSecureOnTheProxysInternalHttpConnection() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var uri = URI.create("http://localhost:" + port + "/login");
            var login = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            String csrfCookie = login.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("XSRF-TOKEN="))
                    .findFirst().orElseThrow();
            assertThat(csrfCookie).contains("Secure", "SameSite=Lax").doesNotContain("HttpOnly");
            var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
            assertThat(matcher.find()).isTrue();
            String form = "username=blackbox&password=" + encode(PASSWORD) + "&_csrf=" + encode(matcher.group(1));
            var signedIn = client.send(HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", csrfCookie.substring(0, csrfCookie.indexOf(';')))
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(signedIn.statusCode()).isEqualTo(302);
            assertThat(signedIn.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("JSESSIONID=")))
                    .anyMatch(value -> value.contains("Secure") && value.contains("HttpOnly") && value.contains("SameSite=Lax"));
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
