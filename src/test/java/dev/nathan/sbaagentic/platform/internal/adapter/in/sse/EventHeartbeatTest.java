package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import jakarta.servlet.AsyncEvent;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Exercise actual emitter serialization and servlet lifecycle callbacks without a timer or database. */
class EventHeartbeatTest {
    private final EventBroadcaster broadcaster = new EventBroadcaster();

    @AfterEach
    void shutdown() {
        broadcaster.closeAll();
    }

    @Test
    void heartbeatsAreCommentsAndRecheckAuthorizationBeforeSending() throws Exception {
        var allowed = new AtomicBoolean(true);
        var stream = open(allowed::get);
        broadcaster.heartbeat();
        assertThat(stream.getResponse().getContentAsString()).isEqualTo(":connected\n\n:heartbeat\n\n");
        allowed.set(false);
        broadcaster.heartbeat();
        assertThat(broadcaster.subscriberCount()).isZero();
        assertThat(stream.getAsyncResult(1000)).isNull();
        assertThat(stream.getResponse().getContentAsString()).isEqualTo(":connected\n\n:heartbeat\n\n");
    }

    @Test
    void completionTimeoutAndErrorEachRemoveTheirEmitterBeforeTheNextHeartbeat() throws Exception {
        for (String signal : new String[] {"complete", "timeout", "error"}) {
            var checks = new AtomicInteger();
            var stream = open(() -> {
                checks.incrementAndGet();

                return true;
            });
            var context = (MockAsyncContext) stream.getRequest().getAsyncContext();
            var event = new AsyncEvent(context, new IOException("fixture disconnect"));
            for (var listener : context.getListeners()) {
                switch (signal) {
                    case "complete" -> listener.onComplete(event);
                    case "timeout" -> listener.onTimeout(event);
                    case "error" -> listener.onError(event);
                }
            }
            int before = checks.get();
            assertThat(broadcaster.subscriberCount()).as(signal).isZero();
            broadcaster.heartbeat();
            assertThat(checks.get())
                    .as(signal + " leaves no stale heartbeat subscriber")
                    .isEqualTo(before);
        }
    }

    @Test
    void brokenSessionDoesNotStopOtherSubscribers() throws Exception {
        var broken = new AtomicBoolean(false);
        var failed = open(() -> {
            if (broken.get()) throw new IllegalStateException("fixture invalid session");

            return true;
        });
        var healthy = open(() -> true);
        broken.set(true);
        broadcaster.heartbeat();
        assertThat(failed.getAsyncResult(1000)).isNull();
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        assertThat(healthy.getResponse().getContentAsString()).contains(":heartbeat\n\n");
    }

    @Test
    void shutdownCompletesStreamsAndLeavesNoHeartbeatRecipients() throws Exception {
        var checks = new AtomicInteger();
        var stream = open(() -> {
            checks.incrementAndGet();

            return true;
        });
        broadcaster.closeAll();
        assertThat(stream.getAsyncResult(1000)).isNull();
        assertThat(broadcaster.subscriberCount()).isZero();
        int before = checks.get();
        broadcaster.heartbeat();
        assertThat(checks.get()).isEqualTo(before);
    }

    private MvcResult open(BooleanSupplier allowed) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FixtureController(broadcaster, allowed))
                .build();

        return mvc.perform(get("/fixture-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @RestController
    static class FixtureController {
        private final EventBroadcaster broadcaster;
        private final BooleanSupplier allowed;

        FixtureController(EventBroadcaster broadcaster, BooleanSupplier allowed) {
            this.broadcaster = broadcaster;
            this.allowed = allowed;
        }

        @GetMapping("/fixture-stream")
        SseEmitter stream() {

            return broadcaster.register(allowed);
        }
    }
}
