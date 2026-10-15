import { describe, expect, it } from "vitest";
import type { AgentSession, EventFacetFields, ProjectSummary } from "../api";
import { parseQuery, QUERY_FIELDS, readQuery, type QueryState } from "../query";
import {
  applyCompletion,
  completionAt,
  countsApplyToDraft,
  fieldOptions,
  isOfferableValue,
  mergeCaseVariants,
  needsRecordedValues,
  quoteCompletionValue,
  SUGGESTION_LIMIT,
  tabAcceptsFirstOption,
  valueOptions,
  type CompletionContext,
  type FieldNameCompletion,
  type ValueCompletion,
  type ValueSources,
} from "../queryCompletion";

/** `|` marks the caret. */
function at(marked: string): CompletionContext | null {
  const caret = marked.indexOf("|");
  return completionAt(marked.replace("|", ""), caret);
}

function valueAt(marked: string): ValueCompletion {
  const context = at(marked);
  if (context?.kind !== "value") throw new Error(`expected a value position: ${marked}`);
  return context;
}

function fieldAt(marked: string): FieldNameCompletion {
  const context = at(marked);
  if (context?.kind !== "field") throw new Error(`expected a field position: ${marked}`);
  return context;
}

function accept(marked: string, insert: string) {
  const caret = marked.indexOf("|");
  const input = marked.replace("|", "");
  const context = completionAt(input, caret);
  if (!context) throw new Error(`nothing to complete: ${marked}`);
  return { input, typedAt: caret, context, ...applyCompletion(input, context, { insert }) };
}

function sources(overrides: Partial<ValueSources> = {}): ValueSources {
  return {
    counts: null,
    showCounts: false,
    recorded: null,
    projects: [],
    pinnedProject: null,
    sessions: null,
    now: Date.parse("2026-10-15T12:00:00Z"),
    ...overrides,
  };
}

function counts(fields: Partial<EventFacetFields>): EventFacetFields {
  return { source: [], kind: [], tool: [], project: [], ...fields };
}

const project = (key: string, extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
  projectKey: key,
  canonicalKey: key,
  label: key,
  sessionCount: 1,
  eventCount: 1,
  savedMeldCount: 0,
  ...extra,
});

const session = (id: string, extra: Partial<AgentSession> = {}): AgentSession => ({
  id,
  source: "codex",
  clientSessionId: `client-${id}`,
  title: "",
  startedAt: "",
  lastSeenAt: "",
  eventCount: 1,
  ...extra,
});

describe("completionAt", () => {
  it("edits the token at the caret, not the last one", () => {
    const context = valueAt("kind:dec| source:claude");
    expect(context).toMatchObject({ field: "kind", name: "kind", prefix: "dec", range: [0, 8] });
  });

  it("keeps a typed alias", () => {
    expect(valueAt("event_type:Dec|")).toMatchObject({ field: "kind", name: "event_type" });
    expect(valueAt("AGENT:co|")).toMatchObject({ field: "source", name: "AGENT", prefix: "co" });
  });

  it("completes field names from the first letter", () => {
    const context = fieldAt("ki|");
    expect(context).toMatchObject({ prefix: "ki", range: [0, 2], explicitOnly: false });
    expect(fieldOptions(context).map((option) => option.insert)).toEqual(["kind:"]);
    expect(fieldOptions(fieldAt("c|")).map((option) => option.insert)).toEqual([
      "cwd:",
      "cwd_exact:",
    ]);
  });

  it("counts offsets in UTF-16 units, like selectionStart", () => {
    expect(valueAt('"😀 bug" kind:dec|')).toMatchObject({ prefix: "dec", range: [9, 17] });
    expect(valueAt('kind:dec| "😀 bug"')).toMatchObject({ prefix: "dec", range: [0, 8] });
  });

  it("reads the prefix up to the caret and replaces the whole segment", () => {
    expect(valueAt("kind:Deci|sion source:claude")).toMatchObject({
      prefix: "Deci",
      range: [0, 13],
    });
  });

  it("edits one comma segment of a list", () => {
    expect(valueAt("source:codex,cl|")).toMatchObject({
      prefix: "cl",
      range: [0, 15],
      head: "source:codex,",
      otherValues: ["codex"],
    });
    expect(valueAt("source:co|dex,claude kind:x")).toMatchObject({
      prefix: "co",
      range: [0, 12],
      head: "source:",
      otherValues: ["claude"],
    });
    expect(valueAt("source:codex,|")).toMatchObject({ prefix: "", range: [0, 13] });
  });

  it("reads a quoted value without its quotes", () => {
    expect(valueAt('project:"/tmp/x/har|bor notes" kind:x')).toMatchObject({
      prefix: "/tmp/x/har",
      range: [0, 29],
      head: "project:",
    });
    expect(valueAt('project:"/tmp/x/harbor notes"|')).toMatchObject({
      prefix: "/tmp/x/harbor notes",
    });
  });

  it("limits an unclosed quote to the text before the caret", () => {
    const context = valueAt('project:"/tmp/x/harb| kind:Decision');
    expect(context).toMatchObject({ prefix: "/tmp/x/harb", range: [0, 20], head: "project:" });
  });

  describe("caret tie-breaks", () => {
    it("prefers the token ending at the caret", () => {
      expect(at("kind:Decision|")?.kind).toBe("value");
    });

    it("edits a token starting at the caret with an empty prefix and its whole name", () => {
      expect(fieldAt("kind:Decision |source:claude")).toMatchObject({
        prefix: "",
        range: [14, 21],
        explicitOnly: true,
      });
      expect(fieldAt("a |recall")).toMatchObject({ prefix: "", range: [2, 8] });
    });

    it("offers nothing until a letter is typed in whitespace or an empty draft", () => {
      expect(fieldAt("kind:Decision  |  source:x")).toMatchObject({
        prefix: "",
        range: [15, 15],
        explicitOnly: true,
      });
      expect(fieldAt("|")).toMatchObject({ prefix: "", range: [0, 0], explicitOnly: true });
    });
  });

  it("returns null where nothing can be completed", () => {
    expect(at('"kind:Dec|"')).toBeNull();
    expect(at("https://ex|ample.com")).toBeNull();
    expect(at("project_group:/tm|p")).toBeNull();
    expect(at("foo.ba|r")).toBeNull();
  });
});

describe("negation follows the parser's own state", () => {
  const negated = (marked: string) => at(marked)?.negated;

  it("treats a leading minus, NOT and lowercase not as negation", () => {
    expect(negated("-kind:obs|")).toBe(true);
    expect(negated("NOT kind:obs|")).toBe(true);
    expect(negated("not source:x|")).toBe(true);
    expect(negated("NOT NOT kind:x|")).toBe(true);
  });

  it("does not treat a quoted NOT, or a NOT consumed by free text, as negation", () => {
    expect(negated('"NOT" kind:x|')).toBe(false);
    expect(negated("NOT recall kind:x|")).toBe(false);
    expect(negated("NOT source: kind:x|")).toBe(false);
  });

  it("offers no values for a negated operator, which the parser reads as text", () => {
    expect(at("NOT session:x|")).toBeNull();
    expect(at("-since:to|")).toBeNull();
    expect(at("-is:a|")).toBeNull();
  });

  it("offers only facets for a negated field name", () => {
    expect(fieldOptions(fieldAt("-s|")).map((option) => option.insert)).toEqual(["source:"]);
    expect(fieldOptions(fieldAt("NOT |")).every((option) => option.insert !== "session:")).toBe(
      true,
    );
    expect(fieldAt("NOT |").negated).toBe(true);
  });

  it("agrees with where parseQuery puts the accepted value", () => {
    for (const marked of [
      "-kind:obs|",
      "NOT kind:obs|",
      "not source:x|",
      '"NOT" kind:x|',
      "NOT NOT kind:x|",
      "NOT recall kind:x|",
      "source:a NOT tool:b|",
    ]) {
      const result = accept(marked, "Value");
      const state = parseQuery(result.text);
      const field = (result.context as ValueCompletion).field as "kind" | "source" | "tool";
      const excluded = state.excludeFacets[field]?.includes("Value") ?? false;
      expect(excluded, marked).toBe(result.context.negated);
    }
  });
});

describe("applyCompletion", () => {
  it("replaces only the edited token and keeps the suffix byte-identical", () => {
    const result = accept("kind:dec| source:claude", "Decision");
    expect(result.text).toBe("kind:Decision source:claude");
    expect(result.caret).toBe(13);
    expect(result.text.slice(result.caret)).toBe(result.input.slice(result.caret - 5));
  });

  it("appends one space only when the range ends at the end of the input", () => {
    expect(accept("kind:Dec|", "Decision")).toMatchObject({
      text: "kind:Decision ",
      caret: 14,
    });
    expect(accept("-kind:obs|", "Observation").text).toBe("-kind:Observation ");
    expect(accept("NOT kind:obs|", "Observation").text).toBe("NOT kind:Observation ");
    expect(accept("source:codex,cl|", "claude").text).toBe("source:codex,claude ");
    expect(accept("kind:x |", "tool:").text).toBe("kind:x tool:");
  });

  it("keeps the rest of a comma list when a middle value is edited", () => {
    expect(accept("source:co|dex,claude kind:x", "codex")).toMatchObject({
      text: "source:codex,claude kind:x",
      caret: 12,
    });
  });

  it("keeps the alias as typed", () => {
    expect(accept("event_type:Dec|", "Decision").text).toBe("event_type:Decision ");
  });

  it("inserts a field name without a space and leaves the caret after the colon", () => {
    expect(accept("ki|", "kind:")).toMatchObject({ text: "kind:", caret: 5 });
    expect(accept("-ki| recall", "kind:")).toMatchObject({ text: "-kind: recall", caret: 6 });
    // A field-name edit swaps the name and keeps the typed value.
    expect(accept("|source:claude", "kind:")).toMatchObject({ text: "kind:claude", caret: 5 });
  });

  it("closes a quoted value and leaves everything after an unclosed quote's caret alone", () => {
    const result = accept('project:"/tmp/x/harb| kind:Decision', "/tmp/x/harbor notes");
    expect(result.text).toBe('project:"/tmp/x/harbor notes" kind:Decision');
    expect(result.text.slice(result.caret)).toBe(result.input.slice(result.caret - 9));
    expect(accept('project:"/tmp/x/harb|', "/tmp/x/harbor notes").text).toBe(
      'project:"/tmp/x/harbor notes" ',
    );
    // A caret inside the quoted text keeps the rest of it verbatim too.
    const inside = accept('project:"/tmp/ha|rbor kind:x', "/tmp/harbor notes");
    expect(inside.text).toBe('project:"/tmp/harbor notes"rbor kind:x');
  });

  it("quotes values with whitespace or commas and never escapes", () => {
    expect(quoteCompletionValue("a b")).toBe('"a b"');
    expect(quoteCompletionValue("a,b")).toBe('"a,b"');
    expect(quoteCompletionValue("plain")).toBe("plain");
    expect(accept("project:sba|", "/tmp/sba agentic").text).toBe('project:"/tmp/sba agentic" ');
    expect(accept("tool:Re|", "Read,Write").text).toBe('tool:"Read,Write" ');
  });

  it("changes the parse only in the edited field", () => {
    const table: Array<[string, string]> = [
      ["kind:dec| source:claude", "Decision"],
      ["source:codex,cl| kind:x", "claude"],
      ["-kind:obs| recall", "Observation"],
      ["NOT kind:obs| recall", "Observation"],
      ["event_type:Dec| tool:Bash", "Decision"],
      ["tool:Ba| last:2h", "Bash"],
      ["kind:Deci|sion source:claude", "Decision"],
      ["source:co|dex,claude kind:Decision", "codex"],
      ['"😀 bug" kind:dec|', "Decision"],
      ['kind:dec| "😀 bug"', "Decision"],
      ['project:"/tmp/x/har|bor" kind:x', "/tmp/x/harbor notes"],
      ["recall last:7| kind:Decision", "7d"],
      ["since:to| kind:Decision", "today"],
      ["session:ab| kind:Decision", "abc-123"],
      ["is:a| source:codex", "all"],
      ["project:sba| kind:Decision", "/Users/nathan/Developer/proj/sba-agentic"],
      ["kind: source:x|", "codex"],
    ];
    for (const [marked, insert] of table) {
      const result = accept(marked, insert);
      const context = result.context as ValueCompletion;
      const edited = readQuery(result.input).tokens.find(
        (token) => token.span.start <= result.typedAt && result.typedAt <= token.span.end,
      );
      const before = withoutField(parseQuery(result.input), context.field);
      // A token the parser read as text before completion leaves the free terms.
      if (edited?.type === "text") {
        before.freeTerms.splice(before.freeTerms.indexOf(edited.term), 1);
      }
      expect(withoutField(parseQuery(result.text), context.field), marked).toEqual(before);
    }
  });
});

function withoutField(state: QueryState, field: ValueCompletion["field"]): QueryState {
  const copy: QueryState = structuredClone(state);
  if (field === "session") copy.session = null;
  else if (field === "since" || field === "last") copy.since = null;
  else if (field === "until") copy.until = null;
  else if (field === "is") copy.isAll = false;
  else {
    delete copy.facets[field];
    delete copy.excludeFacets[field];
  }
  return copy;
}

describe("valueOptions", () => {
  const kinds = counts({
    kind: [
      { value: "Decision", count: 12 },
      { value: "Handoff", count: 3 },
    ],
  });

  it("lists exactly the counted values for an empty prefix", () => {
    const options = valueOptions(valueAt("kind:|"), sources({ counts: kinds, showCounts: true }));
    expect(options.map((option) => [option.label, option.count, option.detail])).toEqual([
      ["Decision", 12, "in current results"],
      ["Handoff", 3, "in current results"],
    ]);
    expect(needsRecordedValues(valueAt("kind:|"), kinds)).toBe(false);
  });

  it("keeps counted values but drops the numbers when the draft scope differs", () => {
    const options = valueOptions(valueAt("kind:|"), sources({ counts: kinds, showCounts: false }));
    expect(options.map((option) => option.count)).toEqual([undefined, undefined]);
  });

  it("merges case variants for source, kind and tool only", () => {
    expect(
      mergeCaseVariants([
        { value: "postToolUse", count: 3 },
        { value: "PostToolUse", count: 40 },
        { value: "Bash", count: 5 },
      ]),
    ).toEqual([
      { value: "PostToolUse", count: 43 },
      { value: "Bash", count: 5 },
    ]);
    const exact = valueOptions(
      valueAt("project_exact:/tmp/|"),
      sources({
        counts: counts({
          project: [
            { value: "/tmp/App", count: 2 },
            { value: "/tmp/app", count: 1 },
          ],
        }),
        showCounts: true,
      }),
    );
    expect(exact.map((option) => [option.label, option.count])).toEqual([
      ["/tmp/App", 2],
      ["/tmp/app", 1],
    ]);
  });

  it("fills from recorded values after counted ones, keeping literal prefixes only", () => {
    const context = valueAt("tool:m_|");
    expect(needsRecordedValues(context, counts({ tool: [{ value: "m_alpha", count: 2 }] }))).toBe(
      true,
    );
    const options = valueOptions(
      context,
      sources({
        counts: counts({ tool: [{ value: "m_alpha", count: 2 }] }),
        showCounts: true,
        // The value index treats _ as a LIKE wildcard: mxbeta is not a literal m_ prefix.
        recorded: ["M_alpha", "m_beta", "mxbeta", 'm_"quoted"', "m_\ttab"],
      }),
    );
    expect(options.map((option) => [option.label, option.detail])).toEqual([
      ["m_alpha", "in current results"],
      ["m_beta", "recorded"],
    ]);
  });

  it("falls back to recorded values while counts are unavailable", () => {
    const options = valueOptions(valueAt("tool:|"), sources({ recorded: ["Bash", "Read"] }));
    expect(options.map((option) => [option.label, option.count, option.detail])).toEqual([
      ["Bash", undefined, "recorded"],
      ["Read", undefined, "recorded"],
    ]);
  });

  it("never offers a value already in the token or one that cannot be typed back", () => {
    const options = valueOptions(
      valueAt("source:codex,|"),
      sources({
        counts: counts({
          source: [
            { value: "codex", count: 9 },
            { value: 'bad"quote', count: 4 },
            { value: "claude", count: 2 },
          ],
        }),
      }),
    );
    expect(options.map((option) => option.label)).toEqual(["claude"]);
    expect(isOfferableValue("line\nbreak")).toBe(false);
    expect(isOfferableValue(" padded")).toBe(false);
  });

  it("offers time operators grammar values only", () => {
    const last = valueOptions(valueAt("last:|"), sources());
    expect(last.map((option) => option.label)).toEqual(["1h", "24h", "7d", "30d"]);
    for (const option of last) {
      expect(parseQuery(`last:${option.insert}`).since?.kind).toBe("duration");
    }
    expect(last[2].detail).toBe("Past 7 days");
    for (const field of ["since", "until"] as const) {
      for (const option of valueOptions(valueAt(`${field}:|`), sources())) {
        expect(parseQuery(`${field}:${option.insert}`)[field]).not.toBeNull();
      }
    }
    expect(valueOptions(valueAt("is:|"), sources()).map((option) => option.insert)).toEqual([
      "all",
    ]);
  });

  it("matches project: by substring and project_exact: by exact prefix from the catalog", () => {
    const catalog = [
      project("/Users/nathan/Developer/proj/sba-agentic", {
        lastSeenAt: "2026-10-15T00:00:00Z",
        scopes: [
          {
            projectKey: "sba",
            canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
            label: "sba",
            primary: true,
          },
          {
            projectKey: "sba-wt",
            canonicalKey: "/tmp/sba-worktree",
            label: "wt",
            primary: false,
          },
        ],
      }),
      project("__no_project__"),
    ];
    const substring = valueOptions(valueAt("project:SBA|"), sources({ projects: catalog }));
    expect(substring.map((option) => [option.label, option.detail])).toEqual([
      ["/Users/nathan/Developer/proj/sba-agentic", "recorded"],
      ["/tmp/sba-worktree", "recorded"],
    ]);
    expect(
      valueOptions(valueAt("project_exact:/tmp/s|"), sources({ projects: catalog })).map(
        (option) => option.label,
      ),
    ).toEqual(["/tmp/sba-worktree"]);
    expect(valueOptions(valueAt("project_exact:/TMP/s|"), sources({ projects: catalog }))).toEqual(
      [],
    );
    // The no-project sentinel only means something as an exact token.
    expect(
      valueOptions(valueAt("project:__no|"), sources({ projects: catalog })).map((o) => o.label),
    ).toEqual([]);
    expect(
      valueOptions(valueAt("project_exact:__no|"), sources({ projects: catalog })).map(
        (option) => option.label,
      ),
    ).toEqual(["__no_project__"]);
  });

  it("shows counts for project_exact: only, because project counts are exact paths", () => {
    const projectCounts = counts({ project: [{ value: "/tmp/app", count: 5 }] });
    const forSubstring = valueOptions(
      valueAt("project:app|"),
      sources({ counts: projectCounts, showCounts: true }),
    );
    expect(forSubstring.map((option) => option.count)).toEqual([undefined]);
    const forExact = valueOptions(
      valueAt("project_exact:/tmp/a|"),
      sources({ counts: projectCounts, showCounts: true }),
    );
    expect(forExact.map((option) => option.count)).toEqual([5]);
  });

  it("shows one note instead of project values while a project is pinned", () => {
    const pinned = project("/Users/nathan/Developer/proj/sba-agentic");
    const options = valueOptions(
      valueAt("cwd:|"),
      sources({ pinnedProject: pinned, projects: [pinned, project("/tmp/other")] }),
    );
    expect(options).toHaveLength(1);
    expect(options[0]).toMatchObject({ kind: "note", insert: "" });
    expect(options[0].label).toContain("sba-agentic");
    // Exclusions are not stripped, so they still offer values, within the pinned project.
    expect(
      valueOptions(
        valueAt("-project:|"),
        sources({ pinnedProject: pinned, projects: [pinned, project("/tmp/other")] }),
      ).map((option) => option.label),
    ).toEqual(["/Users/nathan/Developer/proj/sba-agentic"]);
  });

  it("offers recent sessions by title, inserting the server id", () => {
    const sessions = [
      session("session-1", {
        title: "Stream work",
        lastSeenAt: "2026-10-15T09:00:00Z",
        cwd: "/Users/nathan/Developer/proj/sba-agentic",
      }),
      session("session-2", { title: "Browse fix", cwd: "/tmp/other" }),
      session("session-3", { title: "", clientSessionId: "client-untitled" }),
    ];
    const options = valueOptions(valueAt("session:|"), sources({ sessions }));
    expect(options.map((option) => [option.label, option.insert, option.detail])).toEqual([
      ["Stream work", "session-1", "last seen 3h ago · session-1"],
      // An unknown last-seen time is omitted rather than shown as "now".
      ["Browse fix", "session-2", "session-2"],
      ["client-untitled", "session-3", "session-3"],
    ]);
    expect(
      valueOptions(valueAt("session:brow|"), sources({ sessions })).map((o) => o.insert),
    ).toEqual(["session-2"]);
    const pinned = project("/Users/nathan/Developer/proj/sba-agentic");
    expect(
      valueOptions(valueAt("session:|"), sources({ sessions, pinnedProject: pinned })).map(
        (option) => option.insert,
      ),
    ).toEqual(["session-1"]);
  });
});

describe("a fully typed value or field name is listed first", () => {
  const tools = counts({
    tool: [
      { value: "TaskUpdate", count: 10 },
      { value: "Task", count: 3 },
    ],
  });
  const inserts = (marked: string, overrides: Partial<ValueSources>) =>
    valueOptions(valueAt(marked), sources(overrides)).map((option) => option.insert);

  it("lists an exact counted value first, whatever its count", () => {
    expect(inserts("tool:Task|", { counts: tools, showCounts: true })).toEqual([
      "Task",
      "TaskUpdate",
    ]);
    // Case-insensitive: the recorded spelling is what gets inserted.
    expect(inserts("tool:task|", { counts: tools })).toEqual(["Task", "TaskUpdate"]);
  });

  it("compares the whole value under the caret, not only the text before it", () => {
    expect(inserts("tool:Ta|sk kind:x", { counts: tools })).toEqual(["Task", "TaskUpdate"]);
    expect(inserts("tool:Task|Update", { counts: tools })).toEqual(["TaskUpdate", "Task"]);
  });

  it("promotes an exact value before the list is cut to the limit", () => {
    const longer = Array.from({ length: SUGGESTION_LIMIT + 1 }, (_, index) => ({
      value: `Task${index}`,
      count: 100 - index,
    }));
    const options = inserts("tool:Task|", {
      counts: counts({ tool: [...longer, { value: "Task", count: 1 }] }),
    });
    expect(options).toHaveLength(SUGGESTION_LIMIT);
    expect(options[0]).toBe("Task");
  });

  it("promotes exact recorded, project and session values too", () => {
    expect(inserts("tool:Task|", { recorded: ["TaskUpdate", "Task"] })).toEqual([
      "Task",
      "TaskUpdate",
    ]);
    const catalog = [
      project("/tmp/app-server", { lastSeenAt: "2026-10-15T00:00:00Z" }),
      project("/tmp/app"),
    ];
    expect(inserts("project:/tmp/app|", { projects: catalog })).toEqual([
      "/tmp/app",
      "/tmp/app-server",
    ]);
    expect(inserts("project_exact:/tmp/app|", { projects: catalog })).toEqual([
      "/tmp/app",
      "/tmp/app-server",
    ]);
    // A typed server id or client session id puts its session first.
    const sessions = [session("abc-2", { title: "abc-1 follow-up" }), session("abc-1")];
    expect(inserts("session:abc-1|", { sessions })).toEqual(["abc-1", "abc-2"]);
    const titled = [session("s-2", { title: "client-s-1 notes" }), session("s-1")];
    expect(inserts("session:client-s-1|", { sessions: titled })).toEqual(["s-1", "s-2"]);
  });

  it("lists a fully typed field name first, judged by the whole name", () => {
    for (const field of QUERY_FIELDS) {
      expect(fieldOptions(fieldAt(`${field.name}|`))[0].insert, field.name).toBe(`${field.name}:`);
    }
    expect(fieldOptions(fieldAt("to|ol_name:Bash"))[0].insert).toBe("tool_name:");
    expect(fieldOptions(fieldAt("CW|D_EXACT:/tmp"))[0].insert).toBe("cwd_exact:");
  });
});

describe("tabAcceptsFirstOption", () => {
  it("needs a typed prefix", () => {
    expect(tabAcceptsFirstOption(valueAt("kind:|"))).toBe(false);
    expect(tabAcceptsFirstOption(valueAt("kind:D|"))).toBe(true);
    expect(tabAcceptsFirstOption(valueAt("tool:Task|"))).toBe(true);
    expect(tabAcceptsFirstOption(fieldAt("ki|"))).toBe(true);
    expect(tabAcceptsFirstOption(fieldAt("sess|"))).toBe(true);
    expect(tabAcceptsFirstOption(fieldAt("a |recall"))).toBe(false);
  });

  it("leaves a whole word that already names a field to a highlight", () => {
    // It may be a search word, so Tab moves on unless the field is highlighted.
    expect(tabAcceptsFirstOption(fieldAt("fix session|"))).toBe(false);
    expect(tabAcceptsFirstOption(fieldAt("this is| a bug"))).toBe(false);
    expect(tabAcceptsFirstOption(fieldAt("Project|"))).toBe(false);
    expect(tabAcceptsFirstOption(fieldAt("ki|nd recall"))).toBe(false);
    expect(tabAcceptsFirstOption(fieldAt("kind|:Decision"))).toBe(false);
  });
});

describe("countsApplyToDraft", () => {
  it("compares the draft minus the edited token with the applied query", () => {
    expect(
      countsApplyToDraft("kind:Decision tool:", valueAt("kind:Decision tool:|"), "kind:Decision"),
    ).toBe(true);
    expect(countsApplyToDraft("kind:Dec", valueAt("kind:Dec|"), "")).toBe(true);
    // Canonical forms: order and spacing do not matter.
    expect(
      countsApplyToDraft(
        "tool: last:2h  kind:x",
        valueAt("tool:| last:2h  kind:x"),
        "kind:x since:2h",
      ),
    ).toBe(true);
  });

  it("drops the numbers when the draft changes another field", () => {
    expect(countsApplyToDraft("source:cursor kind:", valueAt("source:cursor kind:|"), "")).toBe(
      false,
    );
  });

  it("removes the NOT that negated the edited token", () => {
    expect(countsApplyToDraft("NOT kind:obs", valueAt("NOT kind:obs|"), "")).toBe(true);
  });
});
