import { describe, expect, it } from "vitest";
import type { EventFacetFields, FacetValueCount } from "../api";
import {
  editDistance,
  interpretQuery,
  meaningfulRecovery,
  suggestField,
  type QueryNote,
} from "../queryInterpretation";

function counts(fields: Partial<EventFacetFields>): EventFacetFields {
  return { source: [], kind: [], tool: [], project: [], ...fields };
}

const completeKinds = counts({
  kind: [
    { value: "Decision", count: 12 },
    { value: "Handoff", count: 3 },
  ],
});

function summary(notes: QueryNote[]) {
  return notes.map((note) => ({
    token: note.token,
    fix: note.fix?.query,
    recovery: note.recovery,
  }));
}

describe("interpretQuery", () => {
  it("explains an unknown field, a unitless duration and a value with no match", () => {
    const query = "knd:Decision kind:Hand recall last:7";
    const notes = interpretQuery(query, { zeroResults: true, counts: completeKinds });

    expect(notes).toHaveLength(3);
    const [field, time, value] = notes;
    expect(field).toMatchObject({ token: "knd:Decision", recovery: false });
    expect(field.text).toContain("there is no knd: field");
    expect(field.text).toContain("Did you mean kind:?");
    expect(field.fix).toEqual({
      label: "Use kind:Decision",
      query: "kind:Decision kind:Hand recall last:7",
    });

    expect(time).toMatchObject({ token: "last:7", recovery: false });
    // The server's diagnostic wording, plus the missing unit.
    expect(time.text).toContain(
      "Invalid time filter. Use since:/until: with an ISO date or timestamp, or last:7d.",
    );
    expect(time.text).toContain("A duration needs a unit");

    expect(value).toMatchObject({ token: "kind:Hand", recovery: true });
    expect(value.text).toContain("Did you mean Handoff?");
    expect(value.fix).toEqual({
      label: "Use kind:Handoff",
      query: "knd:Decision kind:Handoff recall last:7",
    });
  });

  it("offers no did-you-mean while counts are unavailable or results exist", () => {
    const query = "knd:Decision kind:Hand recall last:7";
    expect(interpretQuery(query, { zeroResults: true, counts: null })).toHaveLength(2);
    expect(interpretQuery(query, { zeroResults: false, counts: completeKinds })).toHaveLength(2);
  });

  it("asserts absence only from a complete counted list", () => {
    const full: FacetValueCount[] = Array.from({ length: 25 }, (_, index) => ({
      value: `Kind${index}`,
      count: 25 - index,
    }));
    expect(
      interpretQuery("kind:Kind9x", { zeroResults: true, counts: counts({ kind: full }) }),
    ).toEqual([]);
    expect(
      interpretQuery("kind:Kind9x", {
        zeroResults: true,
        counts: counts({ kind: full.slice(0, 24) }),
      }).map((note) => note.fix?.query),
    ).toEqual(["kind:Kind9"]);
  });

  it("never second-guesses session: or project: values, or exclusions", () => {
    const everything = counts({
      source: [{ value: "codex", count: 1 }],
      kind: [{ value: "Decision", count: 1 }],
      project: [{ value: "/tmp/app", count: 1 }],
    });
    for (const query of ["session:abc", "project:/tmp/ap", "-kind:Dec", "NOT source:cod"]) {
      expect(interpretQuery(query, { zeroResults: true, counts: everything }), query).toEqual([]);
    }
  });

  it("matches case-insensitively and rewrites only the one comma value, by span", () => {
    const query = 'kind:decision,Hand  -tool:Read "a  b"';
    const notes = interpretQuery(query, { zeroResults: true, counts: completeKinds });
    expect(summary(notes)).toEqual([
      {
        token: "kind:Hand",
        fix: 'kind:decision,Handoff  -tool:Read "a  b"',
        recovery: true,
      },
    ]);
  });

  it("suggests a close spelling when no value starts with the typed text", () => {
    const notes = interpretQuery("source:cdoex", {
      zeroResults: true,
      counts: counts({ source: [{ value: "codex", count: 4 }] }),
    });
    expect(notes.map((note) => note.fix?.query)).toEqual(["source:codex"]);
  });

  it("suggests a field only for a unique best match within distance 2 of 3+ letters", () => {
    expect(suggestField("knd")).toBe("kind");
    expect(suggestField("sorce")).toBe("source");
    expect(suggestField("tools")).toBe("tool");
    expect(suggestField("agnet")).toBe("agent");
    expect(suggestField("sinl")).toBeNull(); // kind and since are both 2 away
    expect(suggestField("ab")).toBeNull();
    expect(suggestField("http")).toBeNull();
    expect(suggestField("zzzzzz")).toBeNull();
    // A negated token can only become a facet.
    expect(suggestField("sinse", true)).toBeNull();
    expect(suggestField("sinse")).toBe("since");
  });

  it("notes unknown fields searched as text, but never URLs or one-letter names", () => {
    expect(summary(interpretQuery("ab:x"))).toEqual([
      { token: "ab:x", fix: undefined, recovery: false },
    ]);
    expect(interpretQuery("https://example.com ftp:x C:\\Users\\x vscode://open")).toEqual([]);
    expect(interpretQuery("-sorce:codex").map((note) => note.fix?.query)).toEqual([
      "-source:codex",
    ]);
  });

  it("notes time values only when they cannot be ISO-shaped", () => {
    expect(interpretQuery("since:banana")[0].text).toContain("Invalid time filter");
    expect(interpretQuery("last:today")[0].text).toContain("Invalid time filter");
    expect(interpretQuery("until:7")[0].text).toContain("A duration needs a unit");
    // The server accepts some date-times the browser parser rejects: no false alarm.
    expect(interpretQuery("since:2026-08-18t14:00")).toEqual([]);
    expect(interpretQuery("since:2026-08-18T14:00:00+01:00:00")).toEqual([]);
  });

  it("notes exclusions the grammar cannot apply", () => {
    const texts = (query: string) => interpretQuery(query).map((note) => [note.token, note.text]);
    expect(texts("-standalone")).toEqual([
      ["-standalone", "was searched as text, minus included: free text can't be excluded."],
    ]);
    expect(texts("NOT recall")).toEqual([
      ["NOT recall", "was searched as text, NOT included: free text can't be excluded."],
    ]);
    expect(texts("-since:today")[0][1]).toContain("Negative time operators are unsupported.");
    expect(texts("NOT session:x")).toEqual([
      ["NOT session:x", "was searched as text: session: can't be negated."],
    ]);
    expect(texts("-is:all")[0][1]).toBe("was searched as text: is: can't be negated.");
    expect(texts("-project_group:/tmp/p")[0][1]).toContain("project_group: can't be negated");
    expect(texts("kind:Decision NOT")).toEqual([
      ["NOT", "has nothing after it to negate, so it was searched as text."],
    ]);
    expect(texts("is:starred")[0][1]).toBe("was searched as text: is: only accepts all.");
    expect(texts("kind:")[0][1]).toBe("has no value, so it was searched as text.");
  });

  it("notes an unclosed quote", () => {
    const notes = interpretQuery('kind:Decision "ask history');
    expect(notes.map((note) => note.token)).toEqual(['"ask history']);
    expect(notes[0].text).toContain("unclosed quote");
  });

  it("notes a lowercase not that negates, but not NOT itself", () => {
    expect(interpretQuery("not source:codex").map((note) => note.token)).toEqual(["not"]);
    expect(interpretQuery("NOT source:codex")).toEqual([]);
    expect(interpretQuery("-source:codex")).toEqual([]);
  });

  it("notes project tokens stripped because a project is pinned", () => {
    const notes = interpretQuery("kind:Decision", {
      strippedProjectTokens: ["project:cockpit"],
      pinnedProjectName: "sba-agentic",
    });
    expect(notes).toHaveLength(1);
    expect(notes[0]).toMatchObject({ token: "project:cockpit", recovery: false });
    expect(notes[0].text).toContain("pinned project sba-agentic");
  });

  it("stays quiet about ordinary queries", () => {
    for (const query of [
      "",
      "recall bug",
      'source:codex,claude kind:Decision "exact phrase" last:7d is:all',
      "NOT kind:PostToolUse session:abc since:2026-08-18",
    ]) {
      expect(interpretQuery(query, { zeroResults: true, counts: completeKinds }), query).toEqual(
        [],
      );
    }
  });
});

describe("meaningfulRecovery", () => {
  it("offers is:all when nothing matched under the meaningful filter", () => {
    const note = meaningfulRecovery("kind:UserPromptSubmit last:24h", {
      zeroResults: true,
      humanOnly: false,
    });
    expect(note?.fix).toEqual({
      label: "Include all events",
      query: "kind:UserPromptSubmit last:24h is:all",
    });
    expect(meaningfulRecovery("", { zeroResults: true, humanOnly: false })?.fix?.query).toBe(
      "is:all",
    );
  });

  it("puts is:all first when an unclosed quote would swallow it", () => {
    expect(
      meaningfulRecovery('kind:x "ask', { zeroResults: true, humanOnly: false })?.fix?.query,
    ).toBe('is:all kind:x "ask');
  });

  it("stays away when rows exist, is:all is already there, or My turns replaces the filter", () => {
    expect(meaningfulRecovery("kind:x", { zeroResults: false, humanOnly: false })).toBeNull();
    expect(meaningfulRecovery("kind:x IS:ALL", { zeroResults: true, humanOnly: false })).toBeNull();
    expect(meaningfulRecovery("kind:x", { zeroResults: true, humanOnly: true })).toBeNull();
  });
});

describe("editDistance", () => {
  it("counts a transposition as one edit", () => {
    expect(editDistance("agnet", "agent")).toBe(1);
    expect(editDistance("knd", "kind")).toBe(1);
    expect(editDistance("", "abc")).toBe(3);
    expect(editDistance("same", "same")).toBe(0);
  });
});
