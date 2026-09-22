package dev.nathan.sbaagentic.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Pins the server-side resolution semantics of §5: since-side dates/keywords resolve to the START
 * of the named period; until-side dates/keywords resolve to the NEXT period start as an exclusive
 * bound ({@code until:yesterday} includes all of yesterday via {@code < today-start}); durations
 * subtract from now on both edges; exact instants resolve edge-independently and inclusively. The
 * clock is fixed at 2026-08-20T15:30:00Z in America/New_York (11:30 local, offset -04:00) so zone
 * handling is exercised, not just UTC.
 */
class TimeSpecResolveTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-20T15:30:00Z"), ZoneId.of("America/New_York"));

    @Test
    void keywordTodayResolvesToStartAndExclusiveEndOfLocalDay() {
        assertThat(since("today").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-20T04:00:00Z"));
        assertThat(since("today").exclusiveEnd()).isFalse();
        assertThat(until("today").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-21T04:00:00Z"));
        assertThat(until("today").exclusiveEnd()).isTrue();
    }

    @Test
    void keywordYesterdayCoversAllOfYesterday() {
        assertThat(since("yesterday").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-19T04:00:00Z"));
        assertThat(until("yesterday").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-20T04:00:00Z"));
        assertThat(until("yesterday").exclusiveEnd()).isTrue();
    }

    @Test
    void isoDateResolvesToPeriodStartOrExclusiveNextStart() {
        assertThat(since("2026-08-18").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-18T04:00:00Z"));
        assertThat(until("2026-08-18").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-19T04:00:00Z"));
        assertThat(until("2026-08-18").exclusiveEnd()).isTrue();
    }

    @Test
    void exactInstantsResolveTheSameOnBothEdgesAndStayInclusive() {
        assertThat(since("2026-08-18T14:00:00Z").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(until("2026-08-18T14:00:00Z").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(until("2026-08-18T14:00:00Z").exclusiveEnd()).isFalse();
    }

    @Test
    void zoneLessDateTimeResolvesInTheClockZone() {
        assertThat(since("2026-08-18T09:00").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-18T13:00:00Z"));
    }

    @Test
    void durationsSubtractFromNowOnBothEdges() {
        assertThat(since("2h").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-20T13:30:00Z"));
        assertThat(since("3d").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-17T15:30:00Z"));
        assertThat(since("1w").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-13T15:30:00Z"));
        assertThat(until("30m").resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-20T15:00:00Z"));
        assertThat(until("30m").exclusiveEnd()).isFalse();
    }

    @Test
    void lastIsSinceDurationSugar() {
        EventQuery parsed = EventQuery.parse("last:2h");
        assertThat(parsed.sinceSpec()).isPresent();
        assertThat(parsed.sinceSpec().orElseThrow().resolve(CLOCK)).isEqualTo(Instant.parse("2026-08-20T13:30:00Z"));
        assertThat(parsed.untilSpec()).isEmpty();
    }

    private static TimeSpec since(String value) {

        return required(TimeSpec.parse(value, TimeSpec.Edge.START));
    }

    private static TimeSpec until(String value) {

        return required(TimeSpec.parse(value, TimeSpec.Edge.END));
    }

    private static TimeSpec required(TimeSpec spec) {
        assertThat(spec).isNotNull();

        return spec;
    }
}
