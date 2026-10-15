// Caret-aware completion for the Stream query box. Pure: every decision here reads the query
// through readQuery (the parser's own token readings), so completion and parsing cannot disagree
// about token boundaries, quotes or negation. Fetching and rendering stay in StreamPage.

import type { AgentSession, EventFacetFields, FacetValueCount, ProjectSummary } from "./api";
import { timeAgo } from "./format";
import {
  NO_PROJECT_SCOPE,
  projectMatchesSession,
  projectScopes,
  projectShortName,
} from "./projects";
import {
  describeTimeSpec,
  facetValueSegments,
  parseQuery,
  QUERY_FIELDS,
  readQuery,
  serializeQuery,
  type FacetKey,
  type OperatorName,
  type TimeSpec,
  type TokenReading,
} from "./query";

export type CompletionField = FacetKey | OperatorName;

/** Completing a field name: `ki|` offers `kind:`. */
export type FieldNameCompletion = {
  kind: "field";
  /** The field-name text typed before the caret (may be empty). */
  prefix: string;
  /** The whole name on both sides of the caret: a bare word, or the name before its colon. */
  word: string;
  /** Replaced on accept: the typed name plus its colon when the token already has one. */
  range: [number, number];
  /** The parser would negate this token; operators cannot be negated, so only facets apply. */
  negated: boolean;
  /** Nothing typed yet (caret in whitespace, an empty draft or a token start): list on request. */
  explicitOnly: boolean;
};

/** Completing a value: `kind:dec|` offers `Decision`. */
export type ValueCompletion = {
  kind: "value";
  field: CompletionField;
  /** The field name exactly as typed, so an alias stays an alias. */
  name: string;
  /** The unquoted value text from the start of the edited segment up to the caret. */
  prefix: string;
  /**
   * The edited segment's whole value as the parser reads it (unquoted, trimmed), on both sides
   * of the caret; for an unclosed quote, the text up to the caret.
   */
  value: string;
  /**
   * Replaced on accept: [token start, segment end), or [token start, caret) when the segment's
   * quote never closed, so everything after the caret stays byte-identical. An unclosed quote is
   * completed only with whitespace or the end of the input after the caret.
   */
  range: [number, number];
  /** Token text kept before the edited segment (`-`, the name, the colon, earlier values). */
  head: string;
  negated: boolean;
  /** The whole edited token, plus the NOT that negated it, for the applied-scope comparison. */
  removal: [number, number];
  /** The token's other comma values, never offered twice. */
  otherValues: string[];
};

export type CompletionContext = FieldNameCompletion | ValueCompletion;

export type CompletionOption = {
  id: string;
  kind: "field" | "value" | "note";
  /** Visible text and the option's accessible name. */
  label: string;
  /** What accepting inserts (a field name with its colon, or a raw value); empty for notes. */
  insert: string;
  /** A secondary line: provenance, help or recency. Kept out of the accessible name. */
  detail?: string;
  /** Shown only when the draft's scope matches the applied query's. */
  count?: number;
};

export const SUGGESTION_LIMIT = 8;
/** Mirror of the server's per-field facet cap (EventFacetCounts.VALUE_LIMIT). */
export const COUNTED_VALUE_LIMIT = 25;

// Grammar values only: each parses into the slot its operator names (see the closure tests).
const TIME_VALUES: Record<"since" | "until" | "last", string[]> = {
  since: ["today", "yesterday", "1h", "24h", "7d", "30d"],
  until: ["today", "yesterday"],
  last: ["1h", "24h", "7d", "30d"],
};

const PROVENANCE_COUNTED = "in current results";
const PROVENANCE_RECORDED = "recorded";

/** Finds what the caret is editing. Null when nothing at the caret can be completed. */
export function completionAt(input: string, caret: number): CompletionContext | null {
  const position = Math.max(0, Math.min(caret, input.length));
  const reading = readQuery(input);
  const tokens = reading.tokens;
  const index = caretTokenIndex(tokens, position);
  if (index < 0) {
    // Caret in whitespace or an empty draft: field names, but only on an explicit open.
    const next = tokens.find((token) => token.span.start > position);
    return {
      kind: "field",
      prefix: "",
      word: "",
      range: [position, position],
      negated: next ? next.negateBefore : reading.trailingNot,
      explicitOnly: true,
    };
  }

  const token = tokens[index];
  const { text, start, end } = token.span;
  const leadingMinus = text.startsWith("-") && text.length > 1;
  const negated = token.negateBefore || leadingMinus;
  const nameStart = start + (leadingMinus ? 1 : 0);
  const named = /^([A-Za-z_]+):/.exec(input.slice(nameStart, end));
  if (named && position > nameStart + named[1].length) {
    return valueCompletion(input, tokens, index, position, nameStart, named[1], negated);
  }

  const typed = input.slice(nameStart, Math.max(position, nameStart));
  if (!/^[A-Za-z_]*$/.test(typed)) return null;
  return {
    kind: "field",
    prefix: typed,
    word: named ? named[1] : input.slice(nameStart, end),
    range: [nameStart, named ? nameStart + named[0].length : end],
    negated,
    explicitOnly: typed === "",
  };
}

// A token ending at the caret wins; otherwise one starting at it, otherwise one containing it.
function caretTokenIndex(tokens: TokenReading[], position: number): number {
  const ending = tokens.findIndex((token) => token.span.end === position);
  if (ending >= 0) return ending;
  const starting = tokens.findIndex((token) => token.span.start === position);
  if (starting >= 0) return starting;
  return tokens.findIndex((token) => token.span.start < position && position < token.span.end);
}

function valueCompletion(
  input: string,
  tokens: TokenReading[],
  index: number,
  position: number,
  nameStart: number,
  name: string,
  negated: boolean,
): ValueCompletion | null {
  const info = QUERY_FIELDS.find((field) => field.name === name.toLowerCase());
  if (!info) return null;
  // A negated operator is free text to the parser: offering values would invent syntax.
  if (negated && info.kind === "operator") return null;

  const token = tokens[index];
  const tokenStart = token.span.start;
  const valueStart = nameStart + name.length + 1;
  const rawValue = input.slice(valueStart, token.span.end);
  const segments =
    info.kind === "facet"
      ? facetValueSegments(rawValue, valueStart)
      : [{ raw: rawValue, start: valueStart, end: token.span.end }];
  const segment =
    segments.find((candidate) => candidate.start <= position && position <= candidate.end) ??
    segments[segments.length - 1];
  // Only the final segment of an unclosed token can hold the open quote (it runs to the end).
  const open = token.span.unclosedQuote && segment.end === token.span.end;
  // An open quote has no end, so neither does a word running on past the caret inside it: rather
  // than guess how much to replace (and close the quote mid-word), offer nothing there.
  if (open && position < input.length && !/\s/.test(input[position])) return null;
  const quoted = input[segment.start] === '"';
  let prefixEnd = position;
  if (quoted && !open) {
    const close = input.indexOf('"', segment.start + 1);
    if (close >= 0 && close < segment.end) prefixEnd = Math.min(position, close);
  }
  const prefix = input.slice(segment.start + (quoted ? 1 : 0), prefixEnd);

  const previous = tokens[index - 1];
  const removalStart =
    token.negateBefore && previous?.type === "not" ? previous.span.start : tokenStart;
  return {
    kind: "value",
    field: info.target,
    name: input.slice(nameStart, nameStart + name.length),
    prefix,
    value: open ? prefix : unquote(segment.raw).trim(),
    range: [tokenStart, open ? position : segment.end],
    head: input.slice(tokenStart, segment.start),
    negated,
    removal: [removalStart, token.span.end],
    otherValues: segments
      .filter((candidate) => candidate !== segment)
      .map((candidate) => unquote(candidate.raw).trim())
      .filter(Boolean),
  };
}

function unquote(value: string): string {
  return value.length >= 2 && value.startsWith('"') && value.endsWith('"')
    ? value.slice(1, -1)
    : value;
}

/**
 * Applies an accepted option. Only the range changes; one space is appended only when the range
 * ends at the end of the input, and the caret lands after the inserted text.
 */
export function applyCompletion(
  input: string,
  context: CompletionContext,
  option: Pick<CompletionOption, "insert">,
): { text: string; caret: number } {
  const [from, to] = context.range;
  const inserted =
    context.kind === "field" ? option.insert : context.head + quoteCompletionValue(option.insert);
  let text = input.slice(0, from) + inserted + input.slice(to);
  let caret = from + inserted.length;
  if (context.kind === "value" && to === input.length) {
    text += " ";
    caret += 1;
  }
  return { text, caret };
}

/** Quotes whitespace and commas. Never escapes: the grammar has no escape character. */
export function quoteCompletionValue(value: string): string {
  return /[\s,]/u.test(value) ? `"${value}"` : value;
}

/** A value can be offered only if typing it back would read as exactly that value. */
export function isOfferableValue(value: string): boolean {
  return (
    value.length > 0 &&
    value.trim() === value &&
    !value.includes('"') &&
    // eslint-disable-next-line no-control-regex -- control characters are exactly what is refused
    !/[\u0000-\u001f\u007f]/u.test(value)
  );
}

/** Field and operator names for a field-name position. Negated tokens only take facets. */
export function fieldOptions(context: FieldNameCompletion): CompletionOption[] {
  const prefix = context.prefix.toLowerCase();
  const options = QUERY_FIELDS.filter(
    (field) => field.name.startsWith(prefix) && (!context.negated || field.kind === "facet"),
  ).map((field): CompletionOption => ({
    id: `field:${field.name}`,
    kind: "field",
    label: `${field.name}:`,
    insert: `${field.name}:`,
    detail: field.help,
  }));
  return context.word ? promoteExact(options, `${context.word}:`) : options;
}

/**
 * Whether Tab may accept the first option with nothing highlighted: only once the edited token
 * has a typed prefix. A whole word that already names a field may be a search word (`fix
 * session`), so Tab turns it into a field only when that option is highlighted.
 */
export function tabAcceptsFirstOption(context: CompletionContext): boolean {
  if (!context.prefix) return false;
  if (context.kind === "value") return true;
  const word = context.word.toLowerCase();
  return !QUERY_FIELDS.some((field) => field.name === word);
}

/**
 * Tab accepts the first option, so a value typed in full goes first (its exact spelling, else a
 * case-insensitive match) and is never swapped for a longer or more frequent one.
 */
function promoteExact(options: CompletionOption[], typed: string): CompletionOption[] {
  if (!typed) return options;
  const lower = typed.toLowerCase();
  let index = options.findIndex((option) => option.insert === typed);
  if (index < 0) index = options.findIndex((option) => option.insert.toLowerCase() === lower);
  if (index <= 0) return options;
  return [options[index], ...options.slice(0, index), ...options.slice(index + 1)];
}

export type ValueSources = {
  /** Facet counts for the applied query; null while unavailable. */
  counts: EventFacetFields | null;
  /** The draft, minus the edited token, has the applied query's canonical form. */
  showCounts: boolean;
  /** /api/search/values results for this field and prefix; null when not loaded. */
  recorded: readonly string[] | null;
  projects: readonly ProjectSummary[];
  pinnedProject: ProjectSummary | null;
  /** Recent sessions fetched for this popover opening; null while loading. */
  sessions: readonly AgentSession[] | null;
  now?: number;
};

/**
 * Values for a value position, grounded in recorded data and labelled with where they came from.
 * A value typed in full is listed first, before the list is cut to the limit.
 */
export function valueOptions(context: ValueCompletion, sources: ValueSources): CompletionOption[] {
  return promoteExact(valueCandidates(context, sources), context.value).slice(0, SUGGESTION_LIMIT);
}

function valueCandidates(context: ValueCompletion, sources: ValueSources): CompletionOption[] {
  switch (context.field) {
    case "session":
      return sessionOptions(context, sources);
    case "since":
    case "until":
    case "last":
      return timeOptions(context.field, context.prefix);
    case "is":
      return "all".startsWith(context.prefix.toLowerCase())
        ? [
            {
              id: "value:all",
              kind: "value",
              label: "all",
              insert: "all",
              detail: "Include events the meaningful filter hides",
            },
          ]
        : [];
    case "project":
      // Submit strips positive project: tokens while a project is pinned (its hidden scope wins),
      // so offering one would add a token that silently disappears.
      if (sources.pinnedProject && !context.negated) return [pinnedProjectNote(sources)];
      return projectOptions(context, sources, false);
    case "project_exact":
      return projectOptions(context, sources, true);
    default:
      return facetOptions(context, context.field, sources);
  }
}

/** Whether the value index should be asked: counts are unavailable, or they leave room. */
export function needsRecordedValues(
  context: CompletionContext | null,
  counts: EventFacetFields | null,
): boolean {
  if (!context || context.kind !== "value" || !isCountedFacet(context.field)) return false;
  if (!counts) return true;
  if (!context.prefix) return false;
  return countedMatches(context, counts[context.field]).length < SUGGESTION_LIMIT;
}

/** The value-index field name for a completion field, or null when the index is not used. */
export function recordedValueField(field: CompletionField): string | null {
  if (field === "source") return "source";
  if (field === "kind") return "event_type";
  if (field === "tool") return "tool_name";
  return null;
}

/**
 * Counts describe the applied query. They are shown only when the draft, minus the edited token
 * (and a NOT that negated it), canonicalizes to the applied visible query.
 */
export function countsApplyToDraft(
  input: string,
  context: CompletionContext | null,
  appliedVisible: string,
): boolean {
  if (!context || context.kind !== "value") return false;
  const [from, to] = context.removal;
  const rest = `${input.slice(0, from)} ${input.slice(to)}`;
  return canonicalQuery(rest) === canonicalQuery(appliedVisible);
}

function canonicalQuery(query: string): string {
  return serializeQuery(parseQuery(query));
}

/**
 * Merges rows that differ only by case — the server lowercases source, kind and tool, so they
 * select the same events — keeping the most frequent spelling and summing the counts.
 */
export function mergeCaseVariants(rows: readonly FacetValueCount[]): FacetValueCount[] {
  const merged = new Map<string, { value: string; count: number; best: number }>();
  for (const row of rows) {
    const key = row.value.toLowerCase();
    const existing = merged.get(key);
    if (!existing) {
      merged.set(key, { value: row.value, count: row.count, best: row.count });
    } else {
      existing.count += row.count;
      if (row.count > existing.best) {
        existing.value = row.value;
        existing.best = row.count;
      }
    }
  }
  return [...merged.values()]
    .map(({ value, count }) => ({ value, count }))
    .sort((left, right) => right.count - left.count || left.value.localeCompare(right.value));
}

function isCountedFacet(field: CompletionField): field is "source" | "kind" | "tool" {
  return field === "source" || field === "kind" || field === "tool";
}

function countedMatches(
  context: ValueCompletion,
  rows: readonly FacetValueCount[],
): FacetValueCount[] {
  const prefix = context.prefix.toLowerCase();
  const taken = new Set(context.otherValues.map((value) => value.toLowerCase()));
  return mergeCaseVariants(rows).filter((row) => {
    const lower = row.value.toLowerCase();
    return lower.startsWith(prefix) && !taken.has(lower) && isOfferableValue(row.value);
  });
}

function facetOptions(
  context: ValueCompletion,
  field: "source" | "kind" | "tool",
  sources: ValueSources,
): CompletionOption[] {
  const options: CompletionOption[] = [];
  const seen = new Set<string>();
  if (sources.counts) {
    for (const row of countedMatches(context, sources.counts[field])) {
      seen.add(row.value.toLowerCase());
      options.push({
        id: `value:${row.value}`,
        kind: "value",
        label: row.value,
        insert: row.value,
        detail: PROVENANCE_COUNTED,
        count: sources.showCounts ? row.count : undefined,
      });
    }
  }
  if (needsRecordedValues(context, sources.counts)) {
    // The value index matches with LIKE, where % and _ are wildcards: keep literal prefixes only.
    const prefix = context.prefix.toLowerCase();
    const taken = new Set(context.otherValues.map((value) => value.toLowerCase()));
    for (const value of sources.recorded ?? []) {
      const lower = value.toLowerCase();
      if (!lower.startsWith(prefix) || seen.has(lower) || taken.has(lower)) continue;
      if (!isOfferableValue(value)) continue;
      seen.add(lower);
      options.push({
        id: `value:${value}`,
        kind: "value",
        label: value,
        insert: value,
        detail: PROVENANCE_RECORDED,
      });
    }
  }
  return options;
}

function projectOptions(
  context: ValueCompletion,
  sources: ValueSources,
  exact: boolean,
): CompletionOption[] {
  // project: matches a substring of the session path (case-insensitive), mirroring the filter;
  // project_exact: must be the exact canonical path, so the typed text is its literal prefix.
  const prefix = context.prefix;
  const needle = prefix.toLowerCase();
  const matches = (value: string) =>
    exact ? value.startsWith(prefix) : value.toLowerCase().includes(needle);
  const taken = new Set(context.otherValues);
  const options: CompletionOption[] = [];
  const seen = new Set<string>();
  const add = (value: string, detail: string, count?: number) => {
    if (seen.has(value) || taken.has(value) || !isOfferableValue(value) || !matches(value)) return;
    // The no-project sentinel only means something as an exact token.
    if (value === NO_PROJECT_SCOPE && !exact) return;
    seen.add(value);
    options.push({
      id: `value:${value}`,
      kind: "value",
      label: value,
      insert: value,
      detail,
      count,
    });
  };
  // Counted rows are exact canonical paths, so their numbers only describe project_exact:.
  for (const row of sources.counts?.project ?? []) {
    add(row.value, PROVENANCE_COUNTED, exact && sources.showCounts ? row.count : undefined);
  }
  const catalog = sources.pinnedProject ? [sources.pinnedProject] : [...sources.projects];
  catalog.sort((left, right) => timestamp(right.lastSeenAt) - timestamp(left.lastSeenAt));
  for (const project of catalog) {
    for (const scope of projectScopes(project)) add(scope.canonicalKey, PROVENANCE_RECORDED);
  }
  return options;
}

function pinnedProjectNote(sources: ValueSources): CompletionOption {
  const name = sources.pinnedProject ? projectShortName(sources.pinnedProject) : "a project";
  return {
    id: "note:pinned-project",
    kind: "note",
    label: `The Stream is pinned to ${name}. Clear the pinned project to filter another one.`,
    insert: "",
  };
}

function sessionOptions(context: ValueCompletion, sources: ValueSources): CompletionOption[] {
  const needle = context.prefix.toLowerCase();
  const typed = context.value.toLowerCase();
  const pinned = sources.pinnedProject;
  const now = sources.now ?? Date.now();
  const exact: CompletionOption[] = [];
  const others: CompletionOption[] = [];
  const seen = new Set<string>();
  for (const session of sources.sessions ?? []) {
    if (seen.has(session.id) || !isOfferableValue(session.id)) continue;
    if (pinned && !projectMatchesSession(pinned, session)) continue;
    const haystacks = [session.id, session.clientSessionId ?? "", session.title ?? ""];
    if (needle && !haystacks.some((value) => value.toLowerCase().includes(needle))) continue;
    seen.add(session.id);
    const age = knownAge(session.lastSeenAt, now);
    const option: CompletionOption = {
      id: `session:${session.id}`,
      kind: "value",
      label: session.title?.trim() || session.clientSessionId || session.id,
      // The server id is unambiguous; a client session id can repeat across sources.
      insert: session.id,
      detail: [age ? `last seen ${age} ago` : null, shortId(session.id)]
        .filter(Boolean)
        .join(" · "),
    };
    // A typed server id or client session id names its session, so that one goes first.
    const ids = [session.id, session.clientSessionId ?? ""];
    (typed && ids.some((id) => id.toLowerCase() === typed) ? exact : others).push(option);
  }
  return [...exact, ...others];
}

function timeOptions(field: "since" | "until" | "last", prefix: string): CompletionOption[] {
  const typed = prefix.toLowerCase();
  return TIME_VALUES[field]
    .filter((value) => value.startsWith(typed))
    .map((value) => {
      const spec: TimeSpec = /^\d/.test(value)
        ? { kind: "duration", value }
        : { kind: "keyword", value };
      return {
        id: `value:${value}`,
        kind: "value",
        label: value,
        insert: value,
        detail: describeTimeSpec(spec, field === "until" ? "until" : "since"),
      };
    });
}

function knownAge(iso: string | null | undefined, now: number): string | null {
  if (!iso || Number.isNaN(Date.parse(iso))) return null;
  return timeAgo(iso, now);
}

function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

function timestamp(value: string | null | undefined): number {
  return value ? Date.parse(value) || 0 : 0;
}
