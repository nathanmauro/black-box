package dev.nathan.sbaagentic.query;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;

/** Nanosecond-preserving SQL keys for canonical UTC Instant text; never converts through database date types. */
public record SqlInstant(String expression) {
    private static final DateTimeFormatter FIXED =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final long YEAR_OFFSET = 1_000_000_000L;

    /** Column is an internal SQL identifier, never request text. */
    public static SqlInstant column(String column, boolean postgres) {
        String position = postgres ? "strpos" : "instr";
        String t = position + "(" + column + ", 'T')";
        String dot = position + "(" + column + ", '.')";
        // Offset the signed year into a fixed-width positive range, including Instant.MIN/MAX.
        // The remaining UTC fields and nine fractional digits compare exactly as text.
        String year = "(CAST(substr(" + column + ", 1, " + t + " - 7) AS BIGINT) + 1000000000)";
        String yearText = postgres ? "lpad(CAST(" + year + " AS TEXT), 10, '0')" : "printf('%010d', " + year + ")";
        String time = "substr(" + column + ", " + t + " - 6, 15) || '.' || CASE WHEN " + dot
                + " = 0 THEN '000000000' ELSE substr(substr(" + column + ", " + dot + " + 1, length(" + column
                + ") - " + dot + " - 1) || '000000000', 1, 9) END";

        return new SqlInstant("(" + yearText + " || " + time + ")");
    }

    public String cursorTuple(String idColumn) {

        return "(" + expression + ", " + idColumn + ")";
    }

    public String descending(String idColumn) {

        return expression + " DESC, " + idColumn + " DESC";
    }

    public String indexColumns() {

        return "(" + expression + ") DESC, id DESC";
    }

    public static void bind(List<Object> arguments, Instant value) {
        arguments.add(key(value));
    }

    public static String key(Instant value) {
        String canonical = FIXED.format(value);
        int yearEnd = canonical.indexOf('T') - 6;
        long year = Long.parseLong(canonical.substring(0, yearEnd));

        return String.format(Locale.ROOT, "%010d", year + YEAR_OFFSET)
                + canonical.substring(yearEnd, canonical.length() - 1);
    }
}
