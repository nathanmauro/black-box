// Explains how the Stream read a submitted query, from the parser's own token readings, and
// offers one-click fixes that rewrite a single token by span. Pure; StreamPage renders the notes.

import type { EventFacetFields, FacetValueCount } from "./api";
import { appendQueryToken, parseQuery, QUERY_FIELDS, readQuery, type TokenReading } from "./query";
import {
  COUNTED_VALUE_LIMIT,
  isOfferableValue,
  mergeCaseVariants,
  quoteCompletionValue,
} from "./queryCompletion";

export type QueryFix = {
  /** Button text. */
  label: string;
  /** The whole visible query with only the one token rewritten. */
  query: string;
};

export type QueryNote = {
  id: string;
  /** The token as typed, shown as code before the text. */
  token?: string;
  text: string;
  fix?: QueryFix;
  /** Zero-result recovery renders in the empty state; the other notes under the chip rail. */
  recovery: boolean;
};

export type InterpretationContext = {
  /** The submitted query loaded without error and returned no rows. */
  zeroResults?: boolean;
  /** Facet counts for the applied query; null or absent while unavailable. */
  counts?: EventFacetFields | null;
  /** Positive project tokens removed because a project is pinned. */
  strippedProjectTokens?: readonly string[];
  pinnedProjectName?: string | null;
};

// Shared with the server's diagnostics (CompactQueryDiagnostics) so the UI and API agree.
const INVALID_TIME =
  "Invalid time filter. Use since:/until: with an ISO date or timestamp, or last:7d.";
const NEGATIVE_TIME = "Negative time operators are unsupported.";
// The browser and server parse ISO date-times slightly differently; anything date-shaped might be
// valid on the server, so only values that cannot be ISO-shaped get a note.
const ISO_SHAPED = /^[+-]?\d{4,}-\d{2}-\d{2}/;
const URL_SCHEMES = new Set(["http", "https", "file", "ftp", "mailto", "ssh", "git"]);
const TIME_OPERATORS = new Set(["since", "until", "last"]);
const MAX_TOKEN_DISPLAY = 48;

export function interpretQuery(query: string, context: InterpretationContext = {}): QueryNote[] {
  const reading = readQuery(query);
  const notes: QueryNote[] = [];
  const recoveries: QueryNote[] = [];

  reading.tokens.forEach((token, index) => {
    const note = tokenNote(query, token, reading.tokens[index - 1], reading.tokens[index + 1]);
    if (note) notes.push(note);
    if (token.span.unclosedQuote) {
      notes.push({
        id: `unclosed:${token.span.start}`,
        token: display(token.span.text),
        text: "has an unclosed quote, so it runs to the end of the query as one piece.",
        recovery: false,
      });
    }
    if (context.zeroResults && context.counts && token.type === "facet") {
      recoveries.push(...didYouMean(query, token, context.counts));
    }
  });

  if (reading.trailingNot) {
    notes.push({
      id: "trailing-not",
      token: "NOT",
      text: "has nothing after it to negate, so it was searched as text.",
      recovery: false,
    });
  }

  (context.strippedProjectTokens ?? []).forEach((token, index) => {
    const pinned = context.pinnedProjectName ? ` ${context.pinnedProjectName}` : "";
    notes.push({
      id: `stripped:${index}`,
      token: display(token),
      text: `was removed: the pinned project${pinned} sets the scope. Clear it to filter another project.`,
      recovery: false,
    });
  });

  return [...notes, ...recoveries];
}

/**
 * The meaningful filter hides some kinds (prompts among them), so an empty result offers is:all.
 * My turns replaces that filter, so is:all would change nothing there.
 */
export function meaningfulRecovery(
  query: string,
  options: { zeroResults: boolean; humanOnly: boolean },
): QueryNote | null {
  if (!options.zeroResults || options.humanOnly || parseQuery(query).isAll) return null;
  return {
    id: "recovery:is-all",
    text: "The meaningful filter hides some kinds of events, such as prompts.",
    fix: { label: "Include all events", query: appendQueryToken(query, "is:all") },
    recovery: true,
  };
}

function tokenNote(
  query: string,
  token: TokenReading,
  previous: TokenReading | undefined,
  next: TokenReading | undefined,
): QueryNote | null {
  const id = `${token.type}:${token.span.start}`;
  if (token.type === "not") {
    if (token.span.text !== "NOT" && next?.type === "facet" && next.negateBefore) {
      return {
        id,
        token: token.span.text,
        text: "negated the next filter: NOT works in any case. Quote it to search for the word.",
        recovery: false,
      };
    }
    return null;
  }
  if (token.type !== "text") return null;

  const typed = token.negateBefore && previous?.type === "not" ? previous.span.text + " " : "";
  const shown = display(typed + token.span.text);
  const name = token.name ?? "";
  switch (token.reason) {
    case "plain": {
      if (token.span.text.startsWith("-") && token.span.text.length > 1) {
        return {
          id,
          token: shown,
          text: "was searched as text, minus included: free text can't be excluded.",
          recovery: false,
        };
      }
      if (typed) {
        return {
          id,
          token: shown,
          text: "was searched as text, NOT included: free text can't be excluded.",
          recovery: false,
        };
      }
      return null;
    }
    case "unknown-field":
      return unknownFieldNote(query, token, shown);
    case "empty-value":
      return {
        id,
        token: shown,
        text: "has no value, so it was searched as text.",
        recovery: false,
      };
    case "negated-operator":
      return {
        id,
        token: shown,
        text: TIME_OPERATORS.has(name)
          ? `was searched as text. ${NEGATIVE_TIME}`
          : `was searched as text: ${name}: can't be negated.`,
        recovery: false,
      };
    case "invalid-value": {
      if (name === "is") {
        return {
          id,
          token: shown,
          text: "was searched as text: is: only accepts all.",
          recovery: false,
        };
      }
      const value = rawValue(token.span.text).replace(/"/g, "").trim();
      if (name !== "last" && ISO_SHAPED.test(value)) return null;
      const unit = /^\d+$/.test(value) ? " A duration needs a unit: m, h, d or w." : "";
      return {
        id,
        token: shown,
        text: `was searched as text. ${INVALID_TIME}${unit}`,
        recovery: false,
      };
    }
  }
}

function unknownFieldNote(
  query: string,
  token: Extract<TokenReading, { type: "text" }>,
  shown: string,
): QueryNote | null {
  const name = token.name ?? "";
  const value = rawValue(token.span.text);
  // One letter (C:\path) and URLs are text by intent; never second-guess them.
  if (name.length < 2 || URL_SCHEMES.has(name) || value.startsWith("//")) return null;
  const note: QueryNote = {
    id: `unknown:${token.span.start}`,
    token: shown,
    text: `was searched as text: there is no ${name}: field.`,
    recovery: false,
  };
  const suggestion = suggestField(name, token.negated);
  if (!suggestion) return note;
  const nameStart = token.span.start + (token.span.text.startsWith("-") ? 1 : 0);
  const typedName = query.slice(nameStart, nameStart + name.length);
  const fixed = query.slice(0, nameStart) + suggestion + query.slice(nameStart + typedName.length);
  const rewritten = fixed.slice(token.span.start, token.span.end - name.length + suggestion.length);
  return {
    ...note,
    text: `${note.text} Did you mean ${suggestion}:?`,
    fix: { label: `Use ${display(rewritten)}`, query: fixed },
  };
}

/** A field is suggested only for a unique best match within distance 2 of a 3+ letter name. */
export function suggestField(name: string, negated = false): string | null {
  const typed = name.toLowerCase();
  if (typed.length < 3 || URL_SCHEMES.has(typed)) return null;
  let best = Number.POSITIVE_INFINITY;
  let matches: string[] = [];
  for (const field of QUERY_FIELDS) {
    if (negated && field.kind !== "facet") continue;
    const distance = editDistance(typed, field.name);
    if (distance < best) {
      best = distance;
      matches = [field.name];
    } else if (distance === best) {
      matches.push(field.name);
    }
  }
  return best <= 2 && matches.length === 1 ? matches[0] : null;
}

function didYouMean(
  query: string,
  token: Extract<TokenReading, { type: "facet" }>,
  counts: EventFacetFields,
): QueryNote[] {
  // Exact-match facets only: project: matches substrings and session: lists are capped, so their
  // absence from counts proves nothing. A full list (under the server's cap) proves absence.
  if (token.negated) return [];
  const field = token.field;
  if (field !== "source" && field !== "kind" && field !== "tool") return [];
  const listed = counts[field];
  if (listed.length >= COUNTED_VALUE_LIMIT) return [];
  const rows = mergeCaseVariants(listed);
  const present = new Set(rows.map((row) => row.value.toLowerCase()));
  const notes: QueryNote[] = [];
  for (const segment of token.segments) {
    const value = unquote(segment.raw).trim();
    if (!value || present.has(value.toLowerCase())) continue;
    const suggestion = suggestValue(value, rows);
    if (!suggestion) continue;
    const replacement = quoteCompletionValue(suggestion);
    notes.push({
      id: `did-you-mean:${segment.start}`,
      token: display(`${token.name}:${value}`),
      text: `matches no ${field} in these results. Did you mean ${suggestion}?`,
      fix: {
        label: `Use ${display(`${token.name}:${replacement}`)}`,
        query: query.slice(0, segment.start) + replacement + query.slice(segment.end),
      },
      recovery: true,
    });
  }
  return notes;
}

// The most frequent value the typed text starts, else a unique close spelling.
function suggestValue(value: string, rows: FacetValueCount[]): string | null {
  const typed = value.toLowerCase();
  const offerable = rows.filter((row) => isOfferableValue(row.value));
  const started = offerable.find((row) => row.value.toLowerCase().startsWith(typed));
  if (started) return started.value;
  if (typed.length < 3) return null;
  let best = Number.POSITIVE_INFINITY;
  let matches: string[] = [];
  for (const row of offerable) {
    const distance = editDistance(typed, row.value.toLowerCase());
    if (distance < best) {
      best = distance;
      matches = [row.value];
    } else if (distance === best) {
      matches.push(row.value);
    }
  }
  return best <= 2 && matches.length === 1 ? matches[0] : null;
}

/** Damerau-Levenshtein distance (optimal string alignment): transpositions cost one edit. */
export function editDistance(left: string, right: string): number {
  const rows = left.length + 1;
  const cols = right.length + 1;
  const table: number[][] = Array.from({ length: rows }, (_, row) =>
    Array.from({ length: cols }, (_, col) => (row === 0 ? col : col === 0 ? row : 0)),
  );
  for (let row = 1; row < rows; row += 1) {
    for (let col = 1; col < cols; col += 1) {
      const cost = left[row - 1] === right[col - 1] ? 0 : 1;
      table[row][col] = Math.min(
        table[row - 1][col] + 1,
        table[row][col - 1] + 1,
        table[row - 1][col - 1] + cost,
      );
      if (
        row > 1 &&
        col > 1 &&
        left[row - 1] === right[col - 2] &&
        left[row - 2] === right[col - 1]
      ) {
        table[row][col] = Math.min(table[row][col], table[row - 2][col - 2] + 1);
      }
    }
  }
  return table[left.length][right.length];
}

function rawValue(tokenText: string): string {
  const colon = tokenText.indexOf(":");
  return colon < 0 ? "" : tokenText.slice(colon + 1);
}

function unquote(value: string): string {
  return value.length >= 2 && value.startsWith('"') && value.endsWith('"')
    ? value.slice(1, -1)
    : value;
}

function display(text: string): string {
  return text.length > MAX_TOKEN_DISPLAY ? `${text.slice(0, MAX_TOKEN_DISPLAY - 1)}…` : text;
}
