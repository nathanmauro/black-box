export type QueryState = {
  facets: Partial<Record<FacetField["key"], string>>;
  excludeFacets: Partial<Record<FacetField["key"], string>>;
  text: string[];
};

export type FacetMode = "include" | "exclude";

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

const ALIASES: Record<string, FacetField["key"]> = {
  source: "source",
  agent: "source",
  kind: "kind",
  event_type: "kind",
  eventType: "kind",
  tool: "tool",
  tool_name: "tool",
  toolName: "tool",
  project: "project",
  cwd: "project",
};

export function parseQuery(q: string): QueryState {
  const facets: Partial<Record<FacetField["key"], string>> = {};
  const excludeFacets: Partial<Record<FacetField["key"], string>> = {};
  const text: string[] = [];
  let negateNext = false;

  for (const token of tokenize(q)) {
    if (token.toLowerCase() === "not") {
      if (negateNext) text.push("NOT");
      negateNext = true;
      continue;
    }

    const leadingMinus = token.startsWith("-") && token.length > 1;
    const negated = negateNext || leadingMinus;
    const candidate = leadingMinus ? token.slice(1) : token;
    const separator = candidate.indexOf(":");
    if (separator > 0) {
      const rawField = candidate.slice(0, separator);
      const field = ALIASES[rawField];
      const value = candidate.slice(separator + 1);
      if (field && value) {
        if (negated) excludeFacets[field] = value;
        else facets[field] = value;
        negateNext = false;
        continue;
      }
    }

    if (negateNext) {
      text.push("NOT");
      negateNext = false;
    }
    if (token) text.push(token);
  }
  if (negateNext) text.push("NOT");
  return { facets, excludeFacets, text };
}

export function serializeQuery(state: QueryState): string {
  const parts: string[] = [];
  for (const field of FACET_FIELDS) {
    const value = state.facets[field.key];
    if (value) parts.push(`${field.key}:${quoteToken(value)}`);
  }
  for (const field of FACET_FIELDS) {
    const value = state.excludeFacets[field.key];
    if (value) parts.push(`NOT ${field.key}:${quoteToken(value)}`);
  }
  for (const token of state.text) {
    if (token) parts.push(quoteToken(token));
  }
  return parts.join(" ");
}

export function setFacet(
  query: string,
  key: FacetField["key"],
  value: string | null,
  mode: FacetMode = "include",
): string {
  const parsed = parseQuery(query);
  const target = mode === "exclude" ? parsed.excludeFacets : parsed.facets;
  const opposite = mode === "exclude" ? parsed.facets : parsed.excludeFacets;
  if (!value) {
    delete target[key];
  } else {
    target[key] = value;
    if (opposite[key] === value) delete opposite[key];
  }
  return serializeQuery(parsed);
}

function tokenize(input: string): string[] {
  const tokens: string[] = [];
  let current = "";
  let quoted = false;
  let escaping = false;
  for (const char of input.trim()) {
    if (escaping) {
      current += char;
      escaping = false;
      continue;
    }
    if (char === "\\") {
      escaping = true;
      continue;
    }
    if (char === '"') {
      quoted = !quoted;
      continue;
    }
    if (!quoted && /\s/.test(char)) {
      if (current) {
        tokens.push(current);
        current = "";
      }
      continue;
    }
    current += char;
  }
  if (current) tokens.push(current);
  return tokens;
}

function quoteToken(value: string): string {
  if (!/[\s"]/u.test(value)) return value;
  return `"${value.replace(/"/g, '\\"')}"`;
}
