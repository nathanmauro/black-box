// Canonical API timestamps are UTC Java Instants. Date loses digits after milliseconds and
// cannot represent the full Instant year range; compare validated fixed-width fields instead.
const UTC_INSTANT = /^([+-]?\d{4,10})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

/** Ascending comparison, or undefined when either value is not a valid canonical UTC instant. */
export function compareCanonicalInstants(
  left: string | null | undefined,
  right: string | null | undefined,
): number | undefined {
  const a = instantKey(left);
  const b = instantKey(right);
  if (a === undefined || b === undefined) return undefined;
  return a < b ? -1 : a > b ? 1 : 0;
}

function instantKey(value: string | null | undefined): string | undefined {
  const match = value?.match(UTC_INSTANT);
  if (!match) return undefined;
  const year = Number(match[1]);
  if (Math.abs(year) > 1_000_000_000) return undefined;
  const yearText =
    year >= 0 && year <= 9999
      ? String(year).padStart(4, "0")
      : `${year < 0 ? "-" : "+"}${String(Math.abs(year)).padStart(4, "0")}`;
  if (match[1] !== yearText) return undefined;
  const month = Number(match[2]);
  const day = Number(match[3]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > days[month - 1] ||
    Number(match[4]) > 23 ||
    Number(match[5]) > 59 ||
    Number(match[6]) > 59
  )
    return undefined;
  return (
    String(year + 1_000_000_000).padStart(10, "0") +
    match.slice(2, 7).join("") +
    (match[7] || "").padEnd(9, "0")
  );
}
