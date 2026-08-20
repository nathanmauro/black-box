package dev.nathan.sbaagentic.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One parsed {@code since:}/{@code until:}/{@code last:} value, kept symbolic until execution so a
 * saved {@code last:2h} means "the last two hours whenever asked". Resolution happens server-side
 * against the supplied {@link Clock}: named periods (ISO dates and the {@code today}/{@code
 * yesterday} keywords) resolve to the {@link Edge#START start} of the period on the since side; on
 * the until side they resolve to the NEXT period start and report {@link #exclusiveEnd()} so
 * compilers emit {@code <} — {@code until:2026-08-18} means {@code < 08-19T00:00}, and a strict
 * bound sidesteps the lexicographic quirk of mixed-precision ISO strings ({@code "…59Z" >
 * "…59.999Z"}) that an inclusive end-minus-epsilon would trip over. Exact instants and durations
 * resolve the same on both edges and stay inclusive.
 */
public record TimeSpec(Kind kind, String value, Edge edge) {

    public enum Kind { ABSOLUTE, DURATION, KEYWORD }

    public enum Edge { START, END }

    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d{1,9})([mhdw])$");
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Parses one raw token value into a spec for the given edge, or {@code null} when the value
     * is not a recognised time form (the caller then keeps the whole token as free text). */
    public static TimeSpec parse(String value, Edge edge) {
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (DURATION_PATTERN.matcher(lower).matches()) {
            return new TimeSpec(Kind.DURATION, lower, edge);
        }
        if ("today".equals(lower) || "yesterday".equals(lower)) {
            return new TimeSpec(Kind.KEYWORD, lower, edge);
        }
        if (parsesAsAbsolute(v)) {
            return new TimeSpec(Kind.ABSOLUTE, v, edge);
        }
        return null;
    }

    /** Duration-only variant used by {@code last:}, which never accepts dates or keywords. */
    public static TimeSpec parseDurationOnly(String value, Edge edge) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return DURATION_PATTERN.matcher(lower).matches() ? new TimeSpec(Kind.DURATION, lower, edge) : null;
    }

    public Instant resolve(Clock clock) {
        ZoneId zone = clock.getZone();
        return switch (kind) {
            case DURATION -> clock.instant().minus(toDuration(value));
            case KEYWORD -> {
                LocalDate day = "today".equals(value) ? LocalDate.now(clock) : LocalDate.now(clock).minusDays(1);
                yield edgeOf(day, zone);
            }
            case ABSOLUTE -> {
                Instant exact = tryExactInstant(value, zone);
                yield exact != null ? exact : edgeOf(LocalDate.parse(value), zone);
            }
        };
    }

    /** True when the resolved instant is a strict upper bound ({@code <}): an until-side named
     * period resolves to the next period's start rather than an inclusive last instant. */
    public boolean exclusiveEnd() {
        return edge == Edge.END
                && (kind == Kind.KEYWORD || (kind == Kind.ABSOLUTE && DATE_PATTERN.matcher(value).matches()));
    }

    private Instant edgeOf(LocalDate day, ZoneId zone) {
        return edge == Edge.START
                ? day.atStartOfDay(zone).toInstant()
                : day.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static boolean parsesAsAbsolute(String value) {
        if (DATE_PATTERN.matcher(value).matches()) {
            try {
                LocalDate.parse(value);
                return true;
            }
            catch (DateTimeParseException ex) {
                return false;
            }
        }
        return tryExactInstant(value, ZoneId.of("UTC")) != null;
    }

    private static Instant tryExactInstant(String value, ZoneId zone) {
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ignored) {
            // Not a zoned instant; try the offset and local forms below.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        }
        catch (DateTimeParseException ignored) {
            // Fall through to the zone-less local form.
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        }
        catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Duration toDuration(String value) {
        Matcher m = DURATION_PATTERN.matcher(value);
        if (!m.matches()) {
            throw new IllegalStateException("Not a duration: " + value);
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            case "w" -> Duration.ofDays(7 * amount);
            default -> throw new IllegalStateException("Not a duration unit: " + value);
        };
    }
}
