package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nathan.sbaagentic.platform.internal.adapter.in.web.security.WebSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SPA client routes forward to {@code index.html} so deep links / hard refreshes resolve.
 */
@WebMvcTest(controllers = SpaForwardingController.class)
@Import(WebSecurityConfiguration.class)
class SpaForwardingTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sessionDeepLinkForwardsToIndex() throws Exception {
        mvc.perform(get("/sessions/abc-123")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void searchRouteForwardsToIndex() throws Exception {
        mvc.perform(get("/search")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void streamRouteForwardsToIndexWithoutShadowingTheSseEndpoint() throws Exception {
        mvc.perform(get("/stream")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/api/stream")).andExpect(status().isNotFound());
    }

    @Test
    void boardRouteForwardsToIndexWithoutShadowingTheTaskApi() throws Exception {
        mvc.perform(get("/board")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/api/tasks")).andExpect(status().isNotFound());
    }

    @Test
    void companionRouteForwardsToIndexWithoutShadowingTheApi() throws Exception {
        mvc.perform(get("/companion")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/api/companion")).andExpect(status().isNotFound());
    }
}
