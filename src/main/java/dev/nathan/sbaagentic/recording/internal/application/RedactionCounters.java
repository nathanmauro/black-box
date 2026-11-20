package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.RedactionStatistics;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free per-channel counters for {@link RedactionService}. LongAdder keeps the hot capture path
 * free of contention; a snapshot may be a few increments behind a concurrent writer, which is fine
 * for diagnostics.
 */
final class RedactionCounters {

    final Channel ingest = new Channel();
    final Channel export = new Channel();
    final Channel summary = new Channel();

    static final class Channel {

        private final LongAdder inspected = new LongAdder();
        private final LongAdder redacted = new LongAdder();
        private final LongAdder chars = new LongAdder();

        /** Records one pass and returns {@code output}, so callers can wrap a return statement. */
        String record(String input, String output) {
            if (input == null) {

                return output;
            }
            inspected.increment();
            chars.add(input.length());
            // Identity first: unchanged text usually comes back as the same instance.
            if (output != input && !input.equals(output)) {
                redacted.increment();
            }

            return output;
        }

        RedactionStatistics.Channel snapshot() {

            return new RedactionStatistics.Channel(inspected.sum(), redacted.sum(), chars.sum());
        }
    }
}
