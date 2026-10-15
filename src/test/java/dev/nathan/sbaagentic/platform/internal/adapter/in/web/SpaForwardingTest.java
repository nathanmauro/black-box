package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nathan.sbaagentic.platform.internal.adapter.in.web.security.WebSecurityConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SPA client routes forward to {@code index.html} so deep links / hard refreshes resolve.
 */
@WebMvcTest(controllers = SpaForwardingController.class)
@Import(WebSecurityConfiguration.class)
@DisplayName("Open browser deep links without taking over API routes")
class SpaForwardingTest {

    @Autowired
    MockMvc mvc;

    @ParameterizedTest(name = "{0} opens the application")
    @ValueSource(strings = {"/sessions/abc-123", "/search", "/stream", "/ideas", "/companion"})
    void browserDeepLinksForwardToIndex(String route) throws Exception {
        mvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest(name = "{0} is never forwarded to the application")
    @ValueSource(strings = {"/api/stream", "/api/tasks", "/api/ideas", "/api/companion"})
    void apiRoutesAreNeverForwardedToIndex(String route) throws Exception {
        mvc.perform(get(route)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("The retired board page no longer opens the application")
    void retiredBoardRouteIsNotForwarded() throws Exception {
        mvc.perform(get("/board")).andExpect(status().isNotFound());
    }
}
