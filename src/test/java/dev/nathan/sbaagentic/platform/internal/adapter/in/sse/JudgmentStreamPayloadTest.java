package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class JudgmentStreamPayloadTest {

    private final EventBroadcaster broadcaster = new EventBroadcaster();

    @AfterEach
    void shutdown() {
        broadcaster.closeAll();
    }

    @Test
    void publishesJudgmentAppendedPayload() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FixtureController(broadcaster))
                .build();
        MvcResult stream = mvc.perform(get("/fixture-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        broadcaster.publishJudgmentAppended(new StreamEvents.JudgmentAppended(
                List.of("event-1", "event-2"),
                "session-1",
                "beat-1",
                "building",
                1.2,
                0.4,
                0.0,
                Map.of("session-2", 0.7),
                "jev",
                "jev-latest",
                "orbit-jev-v1",
                "2026-09-21T12:00:00Z"));

        assertThat(stream.getResponse().getContentAsString())
                .contains("event:judgment.appended")
                .contains("\"beatId\":\"beat-1\"")
                .contains("\"phase\":\"building\"")
                .contains("\"kin\":{\"session-2\":0.7}");
    }

    @RestController
    static class FixtureController {
        private final EventBroadcaster broadcaster;

        FixtureController(EventBroadcaster broadcaster) {
            this.broadcaster = broadcaster;
        }

        @GetMapping("/fixture-stream")
        SseEmitter stream() {

            return broadcaster.register();
        }
    }
}
