export type QueryState = {
  facets: Record<string, string>;
  text: string[];
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
  const facets: Record<string, string> = {};
  const text: string[] = [];
  for (const token of tokenize(q)) {
    const separator = token.indexOf(":");
    if (separator > 0) {
      const rawField = token.slice(0, separator);
      const field = ALIASES[rawField];
      const value = token.slice(separator + 1);
      if (field && value) {
        facets[field] = value;
        continue;
      }
    }
    if (token) text.push(token);
  }
  return { facets, text };
}

export function serializeQuery(state: QueryState): string {
  const parts: string[] = [];
  for (const field of FACET_FIELDS) {
    const value = state.facets[field.key];
    if (value) parts.push(`${field.key}:${quoteToken(value)}`);
  }
  for (const token of state.text) {
    if (token) parts.push(quoteToken(token));
  }
  return parts.join(" ");
}

export function setFacet(query: string, key: FacetField["key"], value: string | null): string {
  const parsed = parseQuery(query);
  if (!value) {
    delete parsed.facets[key];
  } else {
    parsed.facets[key] = value;
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
