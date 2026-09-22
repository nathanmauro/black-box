package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

class StreamAuthorizationTest {
    @Test
    void logoutBetweenAuthenticationAndControllerCannotBecomeUnrestrictedLocalAccess() {
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "browser-user");
        assertThat(guard(request, Clock.systemUTC()).getAsBoolean()).isFalse();
    }

    @Test
    void browserStreamsRejectExpiredAndInvalidatedSessions() {
        var session = new MockHttpSession();
        session.setMaxInactiveInterval(30);
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "browser-user");
        request.setSession(session);
        Clock beforeExpiry = Clock.fixed(Instant.ofEpochMilli(session.getLastAccessedTime() + 29_000), ZoneOffset.UTC);
        Clock afterExpiry = Clock.fixed(Instant.ofEpochMilli(session.getLastAccessedTime() + 31_000), ZoneOffset.UTC);
        var validGuard = guard(request, beforeExpiry);
        assertThat(validGuard.getAsBoolean()).isTrue();
        assertThat(guard(request, afterExpiry).getAsBoolean()).isFalse();
        session.invalidate();
        assertThat(validGuard.getAsBoolean()).isFalse();
    }

    @Test
    void localAndStatelessBearerStreamsDoNotRequireBrowserSessions() {
        var local = new MockHttpServletRequest();
        assertThat(guard(local, Clock.systemUTC()).getAsBoolean()).isTrue();
        var bearer = new MockHttpServletRequest();
        bearer.setUserPrincipal(() -> "agent-user");
        bearer.addHeader("Authorization", "Bearer fixture-only");
        assertThat(guard(bearer, Clock.systemUTC()).getAsBoolean()).isTrue();
    }

    private static BooleanSupplier guard(MockHttpServletRequest request, Clock clock) {
        var broadcaster = mock(EventBroadcaster.class);
        new StreamController(broadcaster, clock).stream(request);
        var captor = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(broadcaster).register(captor.capture());

        return captor.getValue();
    }
}
