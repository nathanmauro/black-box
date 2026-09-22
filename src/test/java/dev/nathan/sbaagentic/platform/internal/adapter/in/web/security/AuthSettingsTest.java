package dev.nathan.sbaagentic.platform.internal.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AuthSettingsTest {
    @Test
    void defaultsPreserveLocalAccessAndProductionCookies() {
        var settings = new AuthSettings(new MockEnvironment());
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.secureCookies()).isTrue();
    }

    @Test
    void enabledAuthenticationFailsClosedWithoutBothIndependentStrongSecrets() {
        var environment = new MockEnvironment().withProperty("SBA_AUTH_ENABLED", "true");
        assertThatThrownBy(() -> new AuthSettings(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SBA_AUTH_PASSWORD");
        String password = UUID.randomUUID().toString() + UUID.randomUUID();
        environment.withProperty("SBA_AUTH_PASSWORD", password);
        assertThatThrownBy(() -> new AuthSettings(environment))
                .hasMessageContaining("SBA_AUTH_API_TOKEN")
                .hasMessageNotContaining(password);
        environment.withProperty("SBA_AUTH_API_TOKEN", password);
        assertThatThrownBy(() -> new AuthSettings(environment))
                .hasMessageContaining("different secrets")
                .hasMessageNotContaining(password);
        environment.withProperty("SBA_AUTH_API_TOKEN", "a".repeat(64));
        assertThatThrownBy(() -> new AuthSettings(environment)).hasMessageContaining("SBA_AUTH_API_TOKEN");
        environment.withProperty("SBA_AUTH_API_TOKEN", UUID.randomUUID().toString() + UUID.randomUUID());
        assertThat(new AuthSettings(environment).enabled()).isTrue();
    }
}
