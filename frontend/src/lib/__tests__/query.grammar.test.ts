import { describe, expect, it } from "vitest";
// The shared golden fixture is the grammar contract: the backend's parameterized JUnit suite and
// this file both run every case, so the Java parser and this TS mirror cannot drift silently.
import cases from "../../../../src/main/resources/query/grammar-cases.json";
import { parseQuery, serializeQuery, type QueryState, type TimeSpec } from "../query";

type FixtureExpected = {
  facets?: Record<string, string[]>;
  excluded?: Record<string, string[]>;
  session?: string;
  since?: TimeSpec;
  until?: TimeSpec;
  isAll?: boolean;
  freeTerms?: string[];
  projectGroups?: string[];
};

type FixtureCase = { name: string; input: string; expected: FixtureExpected };

const fixtureCases = cases as FixtureCase[];

// Absent keys in a fixture case mean empty/null/false — expand to the full parser state.
function expectedState(expected: FixtureExpected): QueryState {
  return {
    facets: (expected.facets ?? {}) as QueryState["facets"],
    excludeFacets: (expected.excluded ?? {}) as QueryState["excludeFacets"],
    session: expected.session ?? null,
    since: expected.since ?? null,
    until: expected.until ?? null,
    isAll: expected.isAll ?? false,
    freeTerms: expected.freeTerms ?? [],
    projectGroups: expected.projectGroups ?? [],
  };
}

describe("grammar fixture parity", () => {
  it("covers the full shared fixture", () => {
    expect(fixtureCases.length).toBe(57);
  });

  for (const testCase of fixtureCases) {
    it(testCase.name, () => {
      expect(parseQuery(testCase.input)).toEqual(expectedState(testCase.expected));
    });
  }
});

describe("grammar fixture round-trip stability", () => {
  for (const testCase of fixtureCases) {
    it(`parse∘serialize∘parse is stable: ${testCase.name}`, () => {
      const once = parseQuery(testCase.input);
      const again = parseQuery(serializeQuery(once));
      expect(again).toEqual(once);
    });
  }
});
