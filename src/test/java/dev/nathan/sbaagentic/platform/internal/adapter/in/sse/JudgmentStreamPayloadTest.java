package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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

    @Test
    void buffersAndDeduplicatesLiveEventsWhileReplayLoads() throws Exception {
        var first = event("first", "2026-09-21T12:00:00Z");
        var second = event("second", "2026-09-21T12:00:01Z");
        var third = event("third", "2026-09-21T12:00:02Z");
        var fixture = new ReplayFixtureController(broadcaster, () -> {
            broadcaster.publishEventAppended(second);
            broadcaster.publishEventAppended(third);

            return List.of(first, second);
        });
        var mvc = MockMvcBuilders.standaloneSetup(fixture).build();
        String output =
                mvc.perform(get("/fixture-replay")).andReturn().getResponse().getContentAsString();
        assertThat(output).containsOnlyOnce("id:2026-09-21T12:00:01Z|second");
        assertThat(output.indexOf("|first")).isLessThan(output.indexOf("|second"));
        assertThat(output.indexOf("|second")).isLessThan(output.indexOf("|third"));
    }

    @Test
    void boundedReplaySignalsAnotherPageAndClosesBeforeLiveTraffic() throws Exception {
        var history = IntStream.rangeClosed(1, EventBroadcaster.REPLAY_LIMIT + 1)
                .mapToObj(i -> event("event-" + i, "2026-09-21T12:00:00Z"))
                .toList();
        var fixture = new ReplayFixtureController(broadcaster, () -> {
            broadcaster.publishEventAppended(event("new-live", "2026-09-21T13:00:00Z"));

            return history;
        });
        var mvc = MockMvcBuilders.standaloneSetup(fixture).build();
        String output =
                mvc.perform(get("/fixture-replay")).andReturn().getResponse().getContentAsString();
        assertThat(output).contains("event:replay.more", "|event-2000").doesNotContain("|event-2001", "new-live");
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    private static StreamEvents.EventAppended event(String id, String time) {

        return new StreamEvents.EventAppended(
                "session", "codex", "Decision", null, "fixture", time, id, "/fixture", "assistant", id, null);
    }

    @RestController
    static class ReplayFixtureController {
        private final EventBroadcaster broadcaster;
        private final Supplier<List<StreamEvents.EventAppended>> replay;

        ReplayFixtureController(EventBroadcaster broadcaster, Supplier<List<StreamEvents.EventAppended>> replay) {
            this.broadcaster = broadcaster;
            this.replay = replay;
        }

        @GetMapping("/fixture-replay")
        SseEmitter stream() {

            return broadcaster.register(() -> true, replay);
        }
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
