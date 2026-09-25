// The TypeScript mirror of the backend grammar-v2 parser (dev.nathan.sbaagentic.query.EventQuery).
// Both implementations are pinned by the shared golden fixture src/main/resources/query/grammar-cases.json;
// every grammar change appends fixture cases and updates both parsers in the same commit.

export type FacetKey = "source" | "kind" | "tool" | "project" | "project_exact";

export type FacetMode = "include" | "exclude";

// A symbolic since:/until:/last: value. Never resolved client-side: relative time resolves
// server-side at execution so a saved "last:2h" means "the last two hours whenever asked".
export type TimeSpec = {
  kind: "absolute" | "duration" | "keyword";
  value: string;
};

export type QueryState = {
  facets: Partial<Record<FacetKey, string[]>>;
  excludeFacets: Partial<Record<FacetKey, string[]>>;
  session: string | null;
  since: TimeSpec | null;
  until: TimeSpec | null;
  isAll: boolean;
  freeTerms: string[];
  projectGroups: string[];
};

export type FacetField = {
  key: "source" | "kind" | "tool" | "project";
  label: string;
  enumerable: boolean;
};

export const FACET_FIELDS: FacetField[] = [
  { key: "source", label: "Source", enumerable: true },
  { key: "kind", label: "Kind", enumerable: true },
  { key: "tool", label: "Tool", enumerable: true },
  { key: "project", label: "Project", enumerable: true },
];

// project_group is routed to the top-level projectGroups list, not the facet records.
const FIELD_ALIASES: Record<string, FacetKey | "project_group"> = {
  source: "source",
  agent: "source",
  kind: "kind",
  event_type: "kind",
  tool: "tool",
  tool_name: "tool",
  project: "project",
  cwd: "project",
  project_exact: "project_exact",
  cwd_exact: "project_exact",
  project_group: "project_group",
};

const FACET_ORDER: FacetKey[] = ["source", "kind", "tool", "project", "project_exact"];

const PREFIXED_TOKEN = /^([A-Za-z_]+):(.*)$/;
const DURATION_PATTERN = /^(\d{1,9})([mhdw])$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const DATETIME_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})?$/;

export function parseQuery(q: string): QueryState {
  const state: QueryState = {
    facets: {},
    excludeFacets: {},
    session: null,
    since: null,
    until: null,
    isAll: false,
    freeTerms: [],
    projectGroups: [],
  };
  let negateNext = false;

  for (const token of tokenize(q ?? "")) {
    if (token.toLowerCase() === "not") {
      if (negateNext) state.freeTerms.push("NOT");
      negateNext = true;
      continue;
    }

    const leadingMinus = token.startsWith("-") && token.length > 1;
    const negated = negateNext || leadingMinus;
    const candidate = leadingMinus ? token.slice(1) : token;
    const match = PREFIXED_TOKEN.exec(candidate);
    if (match) {
      const name = match[1].toLowerCase();
      const rawValue = match[2];
      let field: FacetKey | "project_group" | null = FIELD_ALIASES[name] ?? null;
      // project_group is a hidden injected facet; a hand-typed negation of it is meaningless,
      // so it stays free text instead of a silently dropped exclusion.
      if (field === "project_group" && negated) {
        field = null;
      }
      if (field) {
        const pieces = splitFacetValue(rawValue);
        if (pieces.length) {
          if (field === "project_group") {
            state.projectGroups.push(...pieces);
          } else {
            const target = negated ? state.excludeFacets : state.facets;
            (target[field] ??= []).push(...pieces);
          }
          negateNext = false;
          continue;
        }
      } else if (!negated) {
        const value = stripQuotes(rawValue).trim();
        if (value) {
          if (name === "session") {
            state.session = value;
            continue;
          }
          if (name === "since" || name === "until") {
            const spec = parseTimeSpec(value);
            if (spec) {
              state[name] = spec;
              continue;
            }
          }
          if (name === "last") {
            const spec = parseDurationOnly(value);
            if (spec) {
              state.since = spec;
              continue;
            }
          }
          if (name === "is" && value.toLowerCase() === "all") {
            state.isAll = true;
            continue;
          }
        }
      }
    }

    if (negateNext) {
      state.freeTerms.push("NOT");
      negateNext = false;
    }
    const term = stripQuotes(token).trim();
    if (term) state.freeTerms.push(term);
  }
  if (negateNext) state.freeTerms.push("NOT");
  return state;
}

export function serializeQuery(state: QueryState): string {
  const parts: string[] = [];
  for (const key of FACET_ORDER) {
    const values = state.facets[key];
    if (values?.length) parts.push(`${key}:${values.map(quoteValue).join(",")}`);
  }
  for (const key of FACET_ORDER) {
    const values = state.excludeFacets[key];
    if (values?.length) parts.push(`NOT ${key}:${values.map(quoteValue).join(",")}`);
  }
  if (state.session) parts.push(`session:${quoteValue(state.session)}`);
  if (state.since) parts.push(`since:${state.since.value}`);
  if (state.until) parts.push(`until:${state.until.value}`);
  if (state.isAll) parts.push("is:all");
  if (state.projectGroups.length) {
    parts.push(`project_group:${state.projectGroups.map(quoteValue).join(",")}`);
  }
  for (const term of state.freeTerms) {
    if (term) parts.push(quoteFreeTerm(term));
  }
  return parts.join(" ");
}

/**
 * Replaces one facet's values (single value, multi-value list, or null/[] to clear) and removes
 * the same values from the opposite mode so include/exclude never contradict.
 */
export function setFacet(
  query: string,
  key: FacetKey,
  value: string | string[] | null,
  mode: FacetMode = "include",
): string {
  const state = parseQuery(query);
  const target = mode === "exclude" ? state.excludeFacets : state.facets;
  const opposite = mode === "exclude" ? state.facets : state.excludeFacets;
  const values = value == null ? [] : (Array.isArray(value) ? value : [value]).filter(Boolean);
  if (!values.length) {
    delete target[key];
  } else {
    target[key] = values;
    const remaining = (opposite[key] ?? []).filter((existing) => !values.includes(existing));
    if (remaining.length) opposite[key] = remaining;
    else delete opposite[key];
  }
  return serializeQuery(state);
}

/** Removes a single value from a multi-value facet, re-serializing the rest. */
export function removeFacetValue(
  query: string,
  key: FacetKey,
  value: string,
  mode: FacetMode = "include",
): string {
  const state = parseQuery(query);
  const target = mode === "exclude" ? state.excludeFacets : state.facets;
  const remaining = (target[key] ?? []).filter((existing) => existing !== value);
  if (remaining.length) target[key] = remaining;
  else delete target[key];
  return serializeQuery(state);
}

const MONTH_NAMES = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];
const DURATION_UNITS: Record<string, string> = { m: "minute", h: "hour", d: "day", w: "week" };

/** Human phrase for a time-token chip: "Past 2 hours", "Since yesterday", "Until Aug 18". */
export function describeTimeSpec(spec: TimeSpec, side: "since" | "until"): string {
  const sideWord = side === "since" ? "Since" : "Until";
  if (spec.kind === "duration") {
    const match = DURATION_PATTERN.exec(spec.value);
    if (!match) return `${sideWord} ${spec.value}`;
    const amount = Number(match[1]);
    const unit = `${DURATION_UNITS[match[2]]}${amount === 1 ? "" : "s"}`;
    return side === "since" ? `Past ${amount} ${unit}` : `Until ${amount} ${unit} ago`;
  }
  if (spec.kind === "keyword") {
    return `${sideWord} ${spec.value}`;
  }
  if (DATE_PATTERN.test(spec.value)) {
    // Format the plain date from its own digits — a Date round-trip would shift it in
    // negative-offset zones (new Date("2026-08-18") is UTC midnight).
    const [year, month, day] = spec.value.split("-").map(Number);
    const suffix = year === new Date().getFullYear() ? "" : `, ${year}`;
    return `${sideWord} ${MONTH_NAMES[month - 1]} ${day}${suffix}`;
  }
  const instant = new Date(spec.value);
  const suffix =
    instant.getFullYear() === new Date().getFullYear() ? "" : ` ${instant.getFullYear()}`;
  const time = `${String(instant.getHours()).padStart(2, "0")}:${String(instant.getMinutes()).padStart(2, "0")}`;
  return `${sideWord} ${MONTH_NAMES[instant.getMonth()]} ${instant.getDate()}${suffix}, ${time}`;
}

/**
 * Client-side approximation of whether an `until:` spec bounds the query strictly in the past.
 * Relative time still resolves server-side at execution (see TimeSpec); this mirrors the same
 * period-end rules locally only to decide UI liveness (the "live paused — historical scope" badge
 * and suppressing live merges), where a boundary miss is harmless — the next explicit reload
 * re-evaluates against the real predicate.
 * - keyword/date forms resolve to the END of the named local period, so `until:yesterday` is past
 *   and `until:today` is still live (it ends at midnight tonight).
 * - durations mean "until N units ago" (server: now − N), so any positive duration is past.
 */
export function resolvesToPastInstant(spec: TimeSpec, now: Date = new Date()): boolean {
  if (spec.kind === "duration") {
    const match = DURATION_PATTERN.exec(spec.value);
    return match !== null && Number(match[1]) > 0;
  }
  if (spec.kind === "keyword") {
    return spec.value === "yesterday";
  }
  if (DATE_PATTERN.test(spec.value)) {
    const [year, month, day] = spec.value.split("-").map(Number);
    return now.getTime() >= new Date(year, month - 1, day + 1).getTime();
  }
  const instant = Date.parse(spec.value);
  return !Number.isNaN(instant) && instant <= now.getTime();
}

function parseTimeSpec(value: string): TimeSpec | null {
  const lower = value.toLowerCase();
  if (DURATION_PATTERN.test(lower)) return { kind: "duration", value: lower };
  if (lower === "today" || lower === "yesterday") return { kind: "keyword", value: lower };
  if (parsesAsAbsolute(value)) return { kind: "absolute", value };
  return null;
}

function parseDurationOnly(value: string): TimeSpec | null {
  const lower = value.toLowerCase();
  return DURATION_PATTERN.test(lower) ? { kind: "duration", value: lower } : null;
}

function parsesAsAbsolute(value: string): boolean {
  if (DATE_PATTERN.test(value)) return isRealDate(value);
  return DATETIME_PATTERN.test(value) && !Number.isNaN(Date.parse(value));
}

function isRealDate(value: string): boolean {
  const [year, month, day] = value.split("-").map(Number);
  if (month < 1 || month > 12 || day < 1) return false;
  return day <= new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/**
 * Splits on whitespace but keeps double-quoted spans together. Quote characters are PRESERVED in
 * the emitted tokens (mirroring the Java tokenizer) so quoted-ness survives to facet parsing:
 * source:"a,b" stays one value while source:a,b splits, and a fully quoted "since:today" is never
 * an operator.
 */
function tokenize(query: string): string[] {
  const tokens: string[] = [];
  let current = "";
  let inQuotes = false;
  for (const char of query) {
    if (char === '"') {
      inQuotes = !inQuotes;
      current += char;
    } else if (!inQuotes && /\s/.test(char)) {
      if (current) {
        tokens.push(current);
        current = "";
      }
    } else {
      current += char;
    }
  }
  if (current) tokens.push(current);
  return tokens;
}

/**
 * Splits a facet value on commas outside double quotes, then unquotes and trims each piece.
 * Empty pieces are dropped.
 */
function splitFacetValue(rawValue: string): string[] {
  const pieces: string[] = [];
  let current = "";
  let inQuotes = false;
  for (const char of rawValue) {
    if (char === '"') {
      inQuotes = !inQuotes;
      current += char;
    } else if (char === "," && !inQuotes) {
      addPiece(pieces, current);
      current = "";
    } else {
      current += char;
    }
  }
  addPiece(pieces, current);
  return pieces;
}

function addPiece(pieces: string[], raw: string): void {
  const piece = stripQuotes(raw).trim();
  if (piece) pieces.push(piece);
}

function stripQuotes(value: string): string {
  if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
    return value.slice(1, -1);
  }
  return value;
}

function quoteValue(value: string): string {
  if (!/[\s",]/u.test(value)) return value;
  return `"${value.replace(/"/g, '\\"')}"`;
}

// A free term must re-parse as exactly itself; anything that would read back as an operator or
// facet token (e.g. a quoted "is:all" kept as text) gets re-quoted.
function quoteFreeTerm(term: string): string {
  if (/[\s",]/u.test(term)) return quoteValue(term);
  const reparsed = parseQuery(term);
  const plain =
    reparsed.freeTerms.length === 1 &&
    reparsed.freeTerms[0] === term &&
    reparsed.session === null &&
    reparsed.since === null &&
    reparsed.until === null &&
    !reparsed.isAll &&
    reparsed.projectGroups.length === 0 &&
    Object.keys(reparsed.facets).length === 0 &&
    Object.keys(reparsed.excludeFacets).length === 0;
  return plain ? term : `"${term}"`;
}
