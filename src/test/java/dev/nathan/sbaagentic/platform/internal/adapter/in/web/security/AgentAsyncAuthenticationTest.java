package dev.nathan.sbaagentic.platform.internal.adapter.in.web.security;

import jakarta.servlet.DispatcherType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the real filter chain twice on the same servlet request, just as an async completion does. */
@WebMvcTest(controllers = AgentAsyncAuthenticationTest.FixtureController.class, properties = "SBA_AUTH_ENABLED=true")
@Import({WebSecurityConfiguration.class, AgentAsyncAuthenticationTest.FixtureController.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AgentAsyncAuthenticationTest {
    private static final String TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    @Autowired MockMvc mvc;
    @Autowired FixtureController controller;

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("SBA_AUTH_PASSWORD", () -> PASSWORD);
        registry.add("SBA_AUTH_API_TOKEN", () -> TOKEN);
    }

    @Test
    void bearerAuthenticationSurvivesDeferredAsyncCompletionWithoutCreatingASession() throws Exception {
        var initial = mvc.perform(endpoint("/api/async-auth-fixture").header("Authorization", "Bearer " + TOKEN))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(initial.getRequest().getSession(false)).isNull();
        assertThat(initial.getResponse().getCookies()).isEmpty();
        controller.pending.setResult(Map.of("result", "completed"));
        var completed = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                .andExpect(content().json("{\"result\":\"completed\"}")).andReturn();
        assertThat(completed.getRequest().getSession(false)).isNull();
        assertThat(completed.getResponse().getCookies()).isEmpty();
        mvc.perform(endpoint("/api/async-auth-fixture"))
                .andExpect(status().isUnauthorized()).andExpect(request().asyncNotStarted());
    }

    @Test
    void bearerSseCompletionRemainsAuthorizedAfterTheResponseHasBeenCommitted() throws Exception {
        var initial = mvc.perform(endpoint("/api/sse-auth-fixture").header("Authorization", "Bearer " + TOKEN))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(initial.getResponse().isCommitted()).isTrue();
        assertThat(initial.getResponse().getContentAsString()).isEqualTo(":connected\n\n");
        controller.stream.complete();
        var completed = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        assertThat(completed.getRequest().getSession(false)).isNull();
        assertThat(completed.getResponse().getCookies()).isEmpty();
        assertThat(completed.getResponse().getContentAsString()).isEqualTo(":connected\n\n");
        mvc.perform(endpoint("/api/sse-auth-fixture"))
                .andExpect(status().isUnauthorized()).andExpect(request().asyncNotStarted());
    }

    @Test
    void asyncDispatchWithoutAnAuthenticatedOriginalRequestIsStillRejected() throws Exception {
        mvc.perform(endpoint("/api/async-auth-fixture").with(request -> { request.setDispatcherType(DispatcherType.ASYNC); return request; })
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidAgentHeadersCannotFallBackToAnAuthenticatedBrowserSession() throws Exception {
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("browser-user", null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(endpoint("/api/sync-auth-fixture").session(session)).andExpect(status().isOk());
        for (String header : new String[]{"Bearer invalid", "Basic invalid", "Bearer", "invalid"}) {
            mvc.perform(endpoint("/api/async-auth-fixture").session(session).header("Authorization", header))
                    .andExpect(status().isUnauthorized()).andExpect(request().asyncNotStarted());
        }
        mvc.perform(endpoint("/api/sync-auth-fixture").session(session)).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder endpoint(String path) {
        // A real servlet dispatch populates servletPath; MockMvc otherwise leaves it empty,
        // which would exercise the browser-page redirect policy instead of the API's JSON 401.
        return get(path).servletPath(path);
    }

    @RestController
    static class FixtureController {
        DeferredResult<Map<String, String>> pending;
        SseEmitter stream;

        @GetMapping("/api/async-auth-fixture")
        DeferredResult<Map<String, String>> deferred() {
            pending = new DeferredResult<>(5000L);
            return pending;
        }

        @GetMapping("/api/sse-auth-fixture")
        SseEmitter stream() throws Exception {
            stream = new SseEmitter(5000L);
            stream.send(SseEmitter.event().comment("connected"));
            return stream;
        }

        @GetMapping("/api/sync-auth-fixture")
        Map<String, String> sync() {
            return Map.of("result", "authenticated");
        }
    }
}
