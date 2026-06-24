package dev.nathan.sbaagentic.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SPA client routes forward to {@code index.html} so deep links / hard refreshes resolve.
 */
@WebMvcTest(controllers = SpaForwardingController.class)
class SpaForwardingTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sessionDeepLinkForwardsToIndex() throws Exception {
        mvc.perform(get("/sessions/abc-123"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void searchRouteForwardsToIndex() throws Exception {
        mvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
