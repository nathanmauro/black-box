import { describe, expect, it } from "vitest";
import cases from "../../../../src/main/resources/query/grammar-cases.json";
import {
  appendQueryToken,
  parseQuery,
  projectIncludeSpans,
  QUERY_FIELDS,
  readQuery,
  stripProjectIncludes,
  tokenize,
  tokenizeSpans,
} from "../query";

const fixtureInputs = (cases as Array<{ input: string }>).map((testCase) => testCase.input);

// Quote shapes the fixture does not cover: unbalanced, embedded and the serializer's \" escapes,
// which the grammar cannot read back.
const quoteEdgeCases = [
  '"ask history',
  'project:"my pro',
  'kind:Decision project:"my pro',
  'tool:a"b',
  'tool:"a\\"b" kind:x',
  'project:"a\\"b" kind:x project:c',
  'project:"/tmp/sba agentic" "ask  history',
  'kind:x "a b" project:y "c',
  'source:"a,b" project:"x,y" -project:z',
];

const stripCases = [
  ...quoteEdgeCases,
  "project:cockpit kind:Decision",
  "kind:Decision project:cockpit",
  "project:a project:b kind:c",
  "cwd:/tmp/x\tkind:y",
  "NOT project:x kind:y",
  "-cwd:y project:z",
  "not project:x",
  "project:a,b kind:c",
  "  project:x  ",
  'last:2H  -kind:x project:a "a  b"',
  "project:",
  "project:,",
  'project:"" kind:x',
];

describe("tokenizeSpans", () => {
  it("reports UTF-16 offsets whose slices are the tokens", () => {
    for (const input of [...fixtureInputs, ...quoteEdgeCases, '"😀 bug" kind:dec']) {
      for (const span of tokenizeSpans(input)) {
        expect(input.slice(span.start, span.end)).toBe(span.text);
      }
    }
  });

  it("counts an astral character before a token in UTF-16 units, like selectionStart", () => {
    // 😀 is one code point but two UTF-16 units: the token starts at 9, not 8.
    expect(tokenizeSpans('"😀 bug" kind:dec')).toEqual([
      { text: '"😀 bug"', start: 0, end: 8, unclosedQuote: false },
      { text: "kind:dec", start: 9, end: 17, unclosedQuote: false },
    ]);
  });

  it("marks only a final token whose quote never closed", () => {
    expect(tokenizeSpans('kind:x project:"my pro kind:y')).toEqual([
      { text: "kind:x", start: 0, end: 6, unclosedQuote: false },
      { text: 'project:"my pro kind:y', start: 7, end: 29, unclosedQuote: true },
    ]);
  });

  it("is the tokenizer parseQuery reads", () => {
    for (const input of fixtureInputs) {
      expect(readQuery(input).tokens.map((token) => token.span.text)).toEqual(tokenize(input));
    }
  });
});

describe("readQuery", () => {
  it("tracks the parser's pending NOT, including lowercase not and NOT NOT", () => {
    const negation = (query: string) =>
      readQuery(query).tokens.map((token) => [token.span.text, token.negateBefore]);
    expect(negation("not source:x kind:y")).toEqual([
      ["not", false],
      ["source:x", true],
      ["kind:y", false],
    ]);
    expect(negation('"NOT" kind:x')).toEqual([
      ['"NOT"', false],
      ["kind:x", false],
    ]);
    expect(negation("NOT NOT kind:x")).toEqual([
      ["NOT", false],
      ["NOT", true],
      ["kind:x", true],
    ]);
  });

  it("says why a field-shaped token fell through to text", () => {
    const reasons = (query: string) =>
      readQuery(query).tokens.map((token) => (token.type === "text" ? token.reason : token.type));
    expect(
      reasons("knd:x source: since:banana -since:today NOT session:x is:starred word"),
    ).toEqual([
      "unknown-field",
      "empty-value",
      "invalid-value",
      "negated-operator",
      "not",
      "negated-operator",
      "invalid-value",
      "plain",
    ]);
  });

  it("reads inherited object member names as unknown fields, as the Java parser does", () => {
    expect(parseQuery("constructor:x toString:y __proto__:z")).toEqual(
      parseQuery('"constructor:x" "toString:y" "__proto__:z"'),
    );
    expect(parseQuery("constructor:x").freeTerms).toEqual(["constructor:x"]);
  });
});

describe("QUERY_FIELDS", () => {
  const samples: Record<string, string> = {
    session: "abc",
    since: "today",
    until: "yesterday",
    last: "7d",
    is: "all",
  };

  it("offers only names that parse as filters, never as free text", () => {
    for (const field of QUERY_FIELDS) {
      const state = parseQuery(`${field.name}:${samples[field.name] ?? "x"}`);
      expect(state.freeTerms, field.name).toEqual([]);
    }
  });

  it("offers every field name the shared fixture uses except the hidden project_group", () => {
    const offered = new Set(QUERY_FIELDS.map((field) => field.name));
    for (const input of fixtureInputs) {
      for (const token of readQuery(input).tokens) {
        if (token.type === "facet" || token.type === "operator") {
          expect(offered.has(token.name), token.name).toBe(true);
        }
      }
    }
    expect(offered.has("project_group")).toBe(false);
  });
});

describe("stripProjectIncludes", () => {
  it("removes positive project:/cwd: tokens by span and keeps every other byte", () => {
    expect(stripProjectIncludes("project:cockpit kind:Decision")).toBe("kind:Decision");
    expect(stripProjectIncludes("kind:Decision project:cockpit")).toBe("kind:Decision");
    expect(stripProjectIncludes("project:a project:b kind:c")).toBe("kind:c");
    expect(stripProjectIncludes('last:2H  -kind:x project:a "a  b"')).toBe(
      'last:2H  -kind:x "a  b"',
    );
    expect(stripProjectIncludes("cwd:/tmp/x\tkind:y")).toBe("kind:y");
  });

  it("keeps exclusions, exact projects and anything without a project include as typed", () => {
    for (const query of [
      "NOT project:x kind:y",
      "-cwd:y",
      "not project:x",
      "project_exact:/tmp/a",
      "  kind:x  ",
      "project:",
    ]) {
      expect(stripProjectIncludes(query)).toBe(query);
    }
  });

  it("leaves a token with an unclosed quote untouched", () => {
    expect(stripProjectIncludes('project:"my pro')).toBe('project:"my pro');
    expect(stripProjectIncludes('kind:Decision project:"my pro')).toBe(
      'kind:Decision project:"my pro',
    );
    expect(stripProjectIncludes('project:a "ask history')).toBe('"ask history');
  });

  it("is idempotent over the fixture plus unbalanced and escaped quotes", () => {
    for (const input of [...fixtureInputs, ...stripCases]) {
      const once = stripProjectIncludes(input);
      expect(stripProjectIncludes(once), input).toBe(once);
    }
  });

  it("changes the parse only by dropping the project includes", () => {
    for (const input of [...fixtureInputs, ...stripCases]) {
      if (tokenizeSpans(input).some((span) => span.unclosedQuote)) continue;
      const expected = parseQuery(input);
      delete expected.facets.project;
      expect(parseQuery(stripProjectIncludes(input)), input).toEqual(expected);
      expect(projectIncludeSpans(stripProjectIncludes(input))).toEqual([]);
    }
  });
});

describe("appendQueryToken", () => {
  it("appends after the query, trimmed, and stands alone on an empty one", () => {
    expect(appendQueryToken("", "is:all")).toBe("is:all");
    expect(appendQueryToken("   ", "is:all")).toBe("is:all");
    expect(appendQueryToken("  kind:x  ", "is:all")).toBe("kind:x is:all");
    expect(appendQueryToken('kind:x "a  b"', "is:all")).toBe('kind:x "a  b" is:all');
    expect(appendQueryToken("kind:x -", "is:all")).toBe("kind:x - is:all");
  });

  it("goes first when an unclosed quote would swallow it or a trailing NOT would negate it", () => {
    expect(appendQueryToken('kind:x "ask', "is:all")).toBe('is:all kind:x "ask');
    expect(appendQueryToken("kind:x NOT", "is:all")).toBe("is:all kind:x NOT");
    expect(appendQueryToken("kind:x not", "project_group:/p")).toBe("project_group:/p kind:x not");
    expect(appendQueryToken("NOT NOT", "is:all")).toBe("is:all NOT NOT");
  });

  it("adds exactly its own reading and leaves the query's reading unchanged", () => {
    const inputs = [
      ...fixtureInputs,
      ...stripCases,
      "kind:x NOT",
      "kind:x not",
      "NOT",
      "NOT NOT",
      'kind:x NOT "ask',
    ];
    for (const input of inputs) {
      const expected = parseQuery(input);
      expect(parseQuery(appendQueryToken(input, "is:all")), input).toEqual({
        ...expected,
        isAll: true,
      });
      const scoped = parseQuery(appendQueryToken(input, "project_group:/p"));
      expect([...scoped.projectGroups].sort(), input).toEqual(
        [...expected.projectGroups, "/p"].sort(),
      );
      expect({ ...scoped, projectGroups: [] }, input).toEqual({ ...expected, projectGroups: [] });
    }
  });
});
