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

export type OperatorName = "session" | "since" | "until" | "last" | "is";

export type QueryFieldInfo = {
  /** The lowercase name typed before the colon. */
  name: string;
  /** What the parser reads it as: a facet key or an operator. */
  target: FacetKey | OperatorName;
  kind: "facet" | "operator";
  /** One line of help, shown beside the name when completing. */
  help: string;
};

// The names a person can type before a colon, canonical names first. The hidden project_group
// facet is deliberately absent: the Stream injects it for a pinned project and never offers it.
export const QUERY_FIELDS: readonly QueryFieldInfo[] = [
  { name: "source", target: "source", kind: "facet", help: "Agent that recorded it (exact)" },
  { name: "kind", target: "kind", kind: "facet", help: "Event kind, such as Decision (exact)" },
  { name: "tool", target: "tool", kind: "facet", help: "Tool name, such as Bash (exact)" },
  { name: "project", target: "project", kind: "facet", help: "Project path contains this text" },
  { name: "project_exact", target: "project_exact", kind: "facet", help: "Exact project path" },
  { name: "session", target: "session", kind: "operator", help: "One session, by id" },
  {
    name: "since",
    target: "since",
    kind: "operator",
    help: "From a date, today, yesterday or a duration ago",
  },
  {
    name: "until",
    target: "until",
    kind: "operator",
    help: "Up to a date, today, yesterday or a duration ago",
  },
  { name: "last", target: "last", kind: "operator", help: "Within a duration, such as 7d" },
  {
    name: "is",
    target: "is",
    kind: "operator",
    help: "is:all includes events the meaningful filter hides",
  },
  { name: "agent", target: "source", kind: "facet", help: "Same as source:" },
  { name: "event_type", target: "kind", kind: "facet", help: "Same as kind:" },
  { name: "tool_name", target: "tool", kind: "facet", help: "Same as tool:" },
  { name: "cwd", target: "project", kind: "facet", help: "Same as project:" },
  { name: "cwd_exact", target: "project_exact", kind: "facet", help: "Same as project_exact:" },
];

const OPERATOR_NAMES: ReadonlySet<string> = new Set(["session", "since", "until", "last", "is"]);

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

/** A token with its UTF-16 offsets into the query: the same coordinates as selectionStart. */
export type TokenSpan = {
  text: string;
  start: number;
  end: number;
  /** True only for a final token whose opening quote never closed: it swallowed the rest. */
  unclosedQuote: boolean;
};

/** One raw comma segment of a facet value, with its UTF-16 offsets into the query. */
export type ValueSegment = { raw: string; start: number; end: number };

/** Why a field-shaped token fell through to free text. */
export type TextReason =
  "plain" | "unknown-field" | "empty-value" | "negated-operator" | "invalid-value";

/**
 * How the parser read one token. `negateBefore` is the parser's pending NOT when it reached the
 * token, so callers can reason about negation exactly as the parser does instead of guessing
 * from neighbouring text.
 */
export type TokenReading =
  | { type: "not"; span: TokenSpan; negateBefore: boolean }
  | {
      type: "facet";
      span: TokenSpan;
      negateBefore: boolean;
      /** The lowercase name as typed (an alias stays an alias). */
      name: string;
      field: FacetKey;
      negated: boolean;
      values: string[];
      segments: ValueSegment[];
    }
  | { type: "project_group"; span: TokenSpan; negateBefore: boolean; values: string[] }
  | {
      type: "operator";
      span: TokenSpan;
      negateBefore: boolean;
      name: OperatorName;
      value: string;
      spec: TimeSpec | null;
    }
  | {
      type: "text";
      span: TokenSpan;
      negateBefore: boolean;
      term: string;
      reason: TextReason;
      /** The lowercase field name for a field-shaped token, else null. */
      name: string | null;
      negated: boolean;
    };

export type QueryReading = { tokens: TokenReading[]; trailingNot: boolean };

/**
 * Reads every token the way the parser does. parseQuery is a fold over this, so anything built on
 * it (completion, interpretation notes, the pinned-project strip) cannot disagree with parsing.
 */
export function readQuery(q: string): QueryReading {
  const tokens: TokenReading[] = [];
  let negateNext = false;

  for (const span of tokenizeSpans(q ?? "")) {
    const token = span.text;
    const negateBefore = negateNext;
    if (token.toLowerCase() === "not") {
      tokens.push({ type: "not", span, negateBefore });
      negateNext = true;
      continue;
    }

    const leadingMinus = token.startsWith("-") && token.length > 1;
    const negated = negateNext || leadingMinus;
    const candidate = leadingMinus ? token.slice(1) : token;
    const match = PREFIXED_TOKEN.exec(candidate);
    let reason: TextReason = "plain";
    let name: string | null = null;
    if (match) {
      name = match[1].toLowerCase();
      const rawValue = match[2];
      const valueOffset = span.start + (leadingMinus ? 1 : 0) + match[1].length + 1;
      let field = fieldAlias(name);
      // project_group is a hidden injected facet; a hand-typed negation of it is meaningless,
      // so it stays free text instead of a silently dropped exclusion.
      if (field === "project_group" && negated) {
        field = null;
      }
      if (field) {
        const segments = facetValueSegments(rawValue, valueOffset);
        const pieces = facetValuePieces(segments);
        if (pieces.length) {
          if (field === "project_group") {
            tokens.push({ type: "project_group", span, negateBefore, values: pieces });
          } else {
            tokens.push({
              type: "facet",
              span,
              negateBefore,
              name,
              field,
              negated,
              values: pieces,
              segments,
            });
          }
          negateNext = false;
          continue;
        }
        reason = "empty-value";
      } else if (negated) {
        reason =
          OPERATOR_NAMES.has(name) || name === "project_group"
            ? "negated-operator"
            : "unknown-field";
      } else {
        const value = stripQuotes(rawValue).trim();
        if (!value) {
          reason = OPERATOR_NAMES.has(name) ? "empty-value" : "unknown-field";
        } else if (name === "session") {
          tokens.push({ type: "operator", span, negateBefore, name, value, spec: null });
          continue;
        } else if (name === "since" || name === "until" || name === "last") {
          const spec = name === "last" ? parseDurationOnly(value) : parseTimeSpec(value);
          if (spec) {
            tokens.push({ type: "operator", span, negateBefore, name, value, spec });
            continue;
          }
          reason = "invalid-value";
        } else if (name === "is") {
          if (value.toLowerCase() === "all") {
            tokens.push({ type: "operator", span, negateBefore, name, value, spec: null });
            continue;
          }
          reason = "invalid-value";
        } else {
          reason = "unknown-field";
        }
      }
    }

    tokens.push({
      type: "text",
      span,
      negateBefore,
      term: stripQuotes(token).trim(),
      reason,
      name,
      negated,
    });
    negateNext = false;
  }
  return { tokens, trailingNot: negateNext };
}

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
  const reading = readQuery(q);
  for (const token of reading.tokens) {
    switch (token.type) {
      case "not":
        // NOT NOT keeps a literal NOT term and still negates the next facet.
        if (token.negateBefore) state.freeTerms.push("NOT");
        break;
      case "facet": {
        const target = token.negated ? state.excludeFacets : state.facets;
        (target[token.field] ??= []).push(...token.values);
        break;
      }
      case "project_group":
        state.projectGroups.push(...token.values);
        break;
      case "operator":
        if (token.name === "session") state.session = token.value;
        else if (token.name === "is") state.isAll = true;
        else if (token.name === "last") state.since = token.spec;
        else state[token.name] = token.spec;
        break;
      case "text":
        // A pending NOT cannot exclude free text; it is kept as a literal term before it.
        if (token.negateBefore) state.freeTerms.push("NOT");
        if (token.term) state.freeTerms.push(token.term);
        break;
    }
  }
  if (reading.trailingNot) state.freeTerms.push("NOT");
  return state;
}

/** The positive project:/cwd: include tokens a pinned project replaces, in query order. */
export function projectIncludeSpans(query: string): TokenSpan[] {
  return readQuery(query)
    .tokens.filter(
      (token) =>
        token.type === "facet" &&
        token.field === "project" &&
        !token.negated &&
        !token.span.unclosedQuote,
    )
    .map((token) => token.span);
}

/**
 * Removes positive project:/cwd: include tokens by span: each token plus one adjacent whitespace
 * run (the following one, else the preceding one), then trims. Nothing is re-serialized, so every
 * other byte stays as typed, a second pass finds nothing left to remove (idempotent), and a token
 * whose quote never closed is left untouched.
 */
export function stripProjectIncludes(query: string): string {
  const spans = projectIncludeSpans(query);
  if (!spans.length) return query;
  let result = "";
  let cursor = 0;
  for (const span of spans) {
    let start = span.start;
    let end = span.end;
    while (end < query.length && /\s/.test(query[end])) end += 1;
    if (end === span.end) {
      while (start > cursor && /\s/.test(query[start - 1])) start -= 1;
    }
    result += query.slice(cursor, start);
    cursor = end;
  }
  return (result + query.slice(cursor)).trim();
}

/**
 * Adds one self-contained, positive token (balanced quotes, not NOT) so it reads as itself:
 * after the trimmed query, unless the query's end would take it over. An unclosed quote swallows
 * what follows and a trailing NOT negates it, so in those cases the token goes first. Nothing
 * else moves, so the query's own tokens read exactly as before.
 */
export function appendQueryToken(query: string, token: string): string {
  const visible = query.trim();
  if (!visible) return token;
  const reading = readQuery(visible);
  const absorbed = reading.trailingNot || reading.tokens.at(-1)?.span.unclosedQuote === true;
  return absorbed ? `${token} ${visible}` : `${visible} ${token}`;
}

// The hidden scope goes first when the visible query ends in an unclosed quote or a bare NOT:
// the quote would swallow it, and both parsers read a negated project_group as free text.
export function appendProjectGroupScope(query: string, canonicalKey: string): string {
  return appendQueryToken(query, `project_group:${quoteHiddenFacet(canonicalKey)}`);
}

// Commas trigger quoting too: an unquoted comma in a facet value now splits into an IN-list, so a
// canonicalKey containing one would fan out into bogus project groups.
function quoteHiddenFacet(value: string): string {
  return /[\s",]/u.test(value) ? `"${value.replace(/"/g, '\\"')}"` : value;
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
 * an operator. There is no escape character, and an unclosed quote swallows the rest.
 *
 * Offsets are UTF-16 code units, the same as an input's selectionStart. Iterating code points
 * finds the same boundaries a per-unit scan would: surrogate halves are neither whitespace nor `"`.
 */
export function tokenizeSpans(query: string): TokenSpan[] {
  const spans: TokenSpan[] = [];
  let start = -1;
  let inQuotes = false;
  let offset = 0;
  for (const char of query) {
    if (char === '"') {
      if (start < 0) start = offset;
      inQuotes = !inQuotes;
    } else if (!inQuotes && /\s/.test(char)) {
      if (start >= 0) {
        spans.push({ text: query.slice(start, offset), start, end: offset, unclosedQuote: false });
        start = -1;
      }
    } else if (start < 0) {
      start = offset;
    }
    offset += char.length;
  }
  if (start >= 0) {
    spans.push({ text: query.slice(start), start, end: query.length, unclosedQuote: inQuotes });
  }
  return spans;
}

/** The token texts alone; one tokenizer serves parsing and caret-aware completion. */
export function tokenize(query: string): string[] {
  return tokenizeSpans(query).map((span) => span.text);
}

/**
 * Splits a raw facet value on commas outside double quotes, keeping every segment (empty ones
 * included) with its offsets; `offset` is where the value starts in the query.
 */
export function facetValueSegments(rawValue: string, offset = 0): ValueSegment[] {
  const segments: ValueSegment[] = [];
  let inQuotes = false;
  let segmentStart = 0;
  for (let index = 0; index < rawValue.length; index += 1) {
    const char = rawValue[index];
    if (char === '"') {
      inQuotes = !inQuotes;
    } else if (char === "," && !inQuotes) {
      segments.push({
        raw: rawValue.slice(segmentStart, index),
        start: offset + segmentStart,
        end: offset + index,
      });
      segmentStart = index + 1;
    }
  }
  segments.push({
    raw: rawValue.slice(segmentStart),
    start: offset + segmentStart,
    end: offset + rawValue.length,
  });
  return segments;
}

/** Unquotes and trims each segment; empty pieces are dropped. */
function facetValuePieces(segments: ValueSegment[]): string[] {
  return segments.map((segment) => stripQuotes(segment.raw).trim()).filter(Boolean);
}

// Own properties only: a name such as "constructor" must read as an unknown field (free text), as
// it does in the Java parser, not as an inherited Object member.
function fieldAlias(name: string): FacetKey | "project_group" | null {
  return Object.hasOwn(FIELD_ALIASES, name) ? FIELD_ALIASES[name] : null;
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
