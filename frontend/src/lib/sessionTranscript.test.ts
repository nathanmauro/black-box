import { describe, expect, it } from "vitest";
import type { AgentEvent } from "./api";
import {
  filterSessionTranscriptTurns,
  isSessionReaderEvent,
  isSessionMemoryEvent,
  mergeSessionEvents,
  sessionConversationRole,
} from "./sessionTranscript";

function event(overrides: Partial<AgentEvent> & Pick<AgentEvent, "id" | "observedAt">): AgentEvent {
  return {
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "response_item",
    ...overrides,
  };
}

describe("session transcript event merging", () => {
  it("prefers recorded ids while deduplicating equivalent messages and tool pairs", () => {
    const recorded = [
      event({
        id: "recorded-tool-result",
        eventType: "PostToolUse",
        role: "tool",
        toolName: "Read",
        toolOutputJson: '{"content":"hello"}',
        observedAt: "2026-08-30T14:00:04Z",
      }),
      event({
        id: "recorded-tool-call",
        eventType: "PreToolUse",
        role: "tool",
        toolName: "Read",
        toolInputJson: '{"path":"README.md"}',
        observedAt: "2026-08-30T14:00:03Z",
      }),
      event({
        id: "recorded-response",
        eventType: "Stop",
        role: "agent",
        text: "The answer is ready.",
        observedAt: "2026-08-30T14:00:02Z",
      }),
      event({
        id: "recorded-prompt",
        eventType: "UserPromptSubmit",
        role: "agent",
        text: "Read the project.",
        observedAt: "2026-08-30T14:00:00Z",
      }),
    ];
    const transcript = [
      event({
        id: "transcript-tool-result",
        role: "tool",
        toolName: "read",
        toolOutputJson: '{ "content": "hello" }',
        observedAt: "2026-08-30T14:00:04.500Z",
      }),
      event({
        id: "transcript-tool-call",
        role: "tool",
        toolName: "read",
        toolInputJson: '{ "path": "README.md" }',
        observedAt: "2026-08-30T14:00:03.500Z",
      }),
      event({
        id: "transcript-response",
        role: "assistant",
        text: "  The answer is ready. ",
        observedAt: "2026-08-30T14:00:02.500Z",
      }),
      event({
        id: "transcript-prompt",
        role: "user",
        text: "READ the project.",
        observedAt: "2026-08-30T14:00:00.500Z",
      }),
    ];

    expect(mergeSessionEvents(recorded, transcript).map((item) => item.id)).toEqual([
      "recorded-tool-result",
      "recorded-tool-call",
      "recorded-response",
      "recorded-prompt",
    ]);
  });

  it("orders exact targets and loaded pages by nanoseconds before origin precedence", () => {
    const target = event({ id: "target", observedAt: "2026-10-03T12:00:00.123456788Z" });
    const prompt = event({ id: "prompt", observedAt: "2026-10-03T12:00:00.123456789Z" });
    const olderPrompt = event({ id: "older-prompt", observedAt: "2026-10-03T12:00:00.123456787Z" });
    const head = mergeSessionEvents([target], [prompt]);
    expect(head.map((item) => item.id)).toEqual(["prompt", "target"]);
    expect(mergeSessionEvents(head, [target, olderPrompt]).map((item) => item.id)).toEqual([
      "prompt",
      "target",
      "older-prompt",
    ]);
  });

  it("keeps recorded precedence and input order for genuinely equal instants", () => {
    const recorded = [
      event({ id: "z", observedAt: "2026-10-03T12:00:00Z" }),
      event({ id: "a", observedAt: "2026-10-03T12:00:00.000000000Z" }),
    ];
    const transcript = [
      event({ id: "b", observedAt: "2026-10-03T12:00:00.000Z" }),
      event({ id: "y", observedAt: "2026-10-03T12:00:00.000000Z" }),
    ];
    expect(mergeSessionEvents(recorded, transcript).map((item) => item.id)).toEqual([
      "z",
      "a",
      "b",
      "y",
    ]);
  });

  it("orders the full canonical year range ahead of invalid timestamps", () => {
    const rows = [
      event({ id: "negative", observedAt: "-1000000000-01-01T00:00:00Z" }),
      event({ id: "invalid", observedAt: "not a timestamp" }),
      event({ id: "positive", observedAt: "+1000000000-12-31T23:59:59.999999999Z" }),
      event({ id: "before-extended", observedAt: "9999-12-31T23:59:59.999999999Z" }),
      event({ id: "extended", observedAt: "+10000-01-01T00:00:00Z" }),
    ];
    expect(mergeSessionEvents(rows, []).map((item) => item.id)).toEqual([
      "positive",
      "extended",
      "before-extended",
      "negative",
      "invalid",
    ]);
  });

  it("retains Date fallback and invalid tie behavior alongside canonical precision", () => {
    const invalid = event({ id: "invalid", observedAt: "invalid" });
    const missing = event({ id: "missing", observedAt: "" });
    Reflect.deleteProperty(missing, "observedAt"); // Simulate a legacy response missing its timestamp.
    const offset = event({ id: "offset", observedAt: "2026-10-03T13:00:00.123+01:00" });
    const equal = event({ id: "equal", observedAt: "2026-10-03T12:00:00.123000000Z" });
    const later = event({ id: "later", observedAt: "2026-10-03T12:00:00.123000001Z" });
    // A fallback between two canonical values must not collapse their precise comparison.
    for (const rows of [
      [equal, offset, later],
      [later, offset, equal],
      [offset, equal, later],
    ]) {
      const merged = mergeSessionEvents([invalid, ...rows], [missing]);
      expect(merged[0].id).toBe("later");
      expect(merged.slice(1, 3).map((item) => item.id)).toEqual(
        rows.filter((item) => item !== later).map((item) => item.id),
      );
      expect(merged.slice(-2).map((item) => item.id)).toEqual(["invalid", "missing"]);
    }
  });

  it("retains the existing semantic duplicate window", () => {
    const recorded = event({
      id: "recorded",
      role: "assistant",
      text: "Repeated answer",
      observedAt: "2026-10-03T12:00:00Z",
    });
    const atBoundary = event({ ...recorded, id: "boundary", observedAt: "2026-10-03T12:00:10Z" });
    const beyondBoundary = event({
      ...recorded,
      id: "beyond",
      observedAt: "2026-10-03T12:00:10.001Z",
    });
    expect(
      mergeSessionEvents([recorded], [atBoundary, beyondBoundary]).map((item) => item.id),
    ).toEqual(["beyond", "recorded"]);
  });

  it("keeps same-text turns that are not clearly the same occurrence and sorts deterministically", () => {
    const recorded = [
      event({
        id: "recorded-later",
        role: "user",
        text: "Try again",
        observedAt: "2026-08-30T14:05:00Z",
      }),
    ];
    const transcript = [
      event({
        id: "transcript-newest",
        role: "assistant",
        text: "Done",
        observedAt: "2026-08-30T14:06:00Z",
      }),
      event({
        id: "transcript-earlier",
        role: "user",
        text: "Try again",
        observedAt: "2026-08-30T14:00:00Z",
      }),
    ];

    expect(mergeSessionEvents(recorded, transcript).map((item) => item.id)).toEqual([
      "transcript-newest",
      "recorded-later",
      "transcript-earlier",
    ]);
  });
});

describe("numeric tool payload identity", () => {
  function mergePayloads(
    left: string,
    right: string,
    field: "toolOutputJson" | "toolInputJson" = "toolOutputJson",
  ) {
    const tool = {
      eventType: "PostToolUse",
      role: "tool",
      toolName: "Lookup",
      turnId: "same-turn",
      observedAt: "2026-10-03T12:00:00Z",
    };
    return mergeSessionEvents(
      [event({ ...tool, id: "recorded", [field]: left })],
      [event({ ...tool, id: "transcript", [field]: right })],
    ).map((item) => item.id);
  }

  it.each([
    ["9007199254740992", "9007199254740993"],
    ["-9007199254740992", "-9007199254740993"],
    ["1", "1.0000000000000001"],
    ["1", "10000000000000001e-16"],
    ["0.1", "0.10000000000000001"],
    ["1e400", "null"],
    ["1e400", "1e401"],
    ["-1e400", "1e400"],
    ["1e-400", "0"],
    ["-1e-400", "0"],
  ])("retains distinct numeric values %s and %s for input and output", (left, right) => {
    for (const field of ["toolOutputJson", "toolInputJson"] as const)
      expect(mergePayloads(`{"reference":${left}}`, `{"reference":${right}}`, field)).toEqual([
        "recorded",
        "transcript",
      ]);
  });

  it.each([
    ["1", "1.0"],
    ["1e2", "100"],
    ["0.10", "1e-1"],
    ["-0", "0.0"],
    ["1e+000002", "100"],
    ["123.4500", "12345e-2"],
  ])("preserves safe key-order and numeric equivalence for %s and %s", (left, right) => {
    expect(mergePayloads(`{"b":2,"a":${left}}`, `{ "a": ${right}, "b": 2 }`)).toEqual(["recorded"]);
  });

  it("compacts outside-string whitespace for identical inexact JSON without normalizing string contents", () => {
    const left = '{"reference":9007199254740993,"label":"two  spaces"}';
    expect(
      mergePayloads(left, '{\n "reference" : 9007199254740993, "label" : "two  spaces"\n}'),
    ).toEqual(["recorded"]);
    expect(mergePayloads(left, left.replace("two  spaces", "two spaces"))).toEqual([
      "recorded",
      "transcript",
    ]);
    // Conservative fallback deliberately retains uncertain key-order/spelling duplicates.
    expect(mergePayloads(left, '{"label":"two  spaces","reference":9007199254740993}')).toEqual([
      "recorded",
      "transcript",
    ]);
    expect(mergePayloads('{"n":9007199254740993}', '{"n":9007199254740993.0}')).toEqual([
      "recorded",
      "transcript",
    ]);
  });

  it("does not scan numbers or structural punctuation inside escaped strings", () => {
    const label = 'quoted "9007199254740993" \\ path 1e400 { [ with  spaces';
    const left = JSON.stringify({ label, count: 1 });
    const right = JSON.stringify({ count: 1.0, label }, null, 2);
    expect(mergePayloads(left, right)).toEqual(["recorded"]);
    const inexact = left.replace('"count":1', '"count":9007199254740993');
    expect(mergePayloads(inexact, inexact.replace('"count":', '"count": '))).toEqual(["recorded"]);
  });

  it("keeps identity categories distinct from arbitrary invalid-text payloads", () => {
    const raw = '{"n":9007199254740993}';
    expect(mergePayloads(raw, `raw-json:${raw}`)).toEqual(["recorded", "transcript"]);
    expect(mergePayloads('{"n":1}', 'json:{"n":1}')).toEqual(["recorded", "transcript"]);
    expect(mergePayloads("invalid   text", "invalid text")).toEqual(["recorded"]);
  });

  it("bounds huge exponent work without expanding decimal powers", () => {
    const exponent = "9".repeat(12_000);
    const left = `{"n":1e${exponent}}`;
    expect(mergePayloads(left, left.replace("1e", "2e"))).toEqual(["recorded", "transcript"]);
    expect(mergePayloads(left, `{ "n" : 1e${exponent} }`)).toEqual(["recorded"]);
    expect(mergePayloads(`{"n":1e-${exponent}}`, '{"n":0}')).toEqual(["recorded", "transcript"]);
  });

  it("uses bounded raw identity for deeply nested JSON and preserves string whitespace", () => {
    const wrap = (text: string) =>
      '{"item":'.repeat(2200) + JSON.stringify(text) + "}".repeat(2200);
    expect(mergePayloads(wrap("two  spaces"), wrap("two spaces"))).toEqual([
      "recorded",
      "transcript",
    ]);
    expect(mergePayloads(wrap("same"), wrap("same").replaceAll('"item":', '"item": '))).toEqual([
      "recorded",
    ]);
  });

  it("handles long inline significands without decimal expansion", () => {
    const value = `1${"0".repeat(30_000)}1e-30001`;
    expect(mergePayloads(`{"n":${value}}`, '{"n":1}')).toEqual(["recorded", "transcript"]);
    expect(mergePayloads(`{"n":${value}}`, `{ "n": ${value} }`)).toEqual(["recorded"]);
  });

  it("keeps the existing bounded long-payload path usable without claiming collision freedom", () => {
    const left = JSON.stringify({ padding: "x".repeat(40_000), n: 9007199254740992 });
    expect(mergePayloads(left, left)).toEqual(["recorded"]);
    expect(mergePayloads(left, left.replace("9007199254740992", "9007199254740993"))).toEqual([
      "recorded",
      "transcript",
    ]);
  });
});

describe("session transcript reading and search", () => {
  it.each(["Projection", "projection", " PROJECTION ", "Evidence", "evidence", " EVIDENCE "])(
    "classifies %s as memory rather than a conversation reply",
    (eventType) => {
      const projection = event({
        id: "projection",
        observedAt: "2026-10-03T12:00:00Z",
        eventType,
        role: "assistant",
        text: "Recorded possibilities",
      });
      expect(isSessionMemoryEvent(projection)).toBe(true);
      expect(isSessionReaderEvent(projection)).toBe(false);
      expect(sessionConversationRole(projection)).toBeNull();
    },
  );

  it("shows user, assistant, and tool records while hiding memory and internal records", () => {
    const prompt = event({
      id: "prompt",
      role: "user",
      text: "Question",
      observedAt: "2026-08-30T14:00:00Z",
    });
    const response = event({
      id: "response",
      role: "assistant",
      text: "Answer",
      observedAt: "2026-08-30T14:00:01Z",
    });
    const tool = event({
      id: "tool",
      role: "tool",
      toolName: "Read",
      observedAt: "2026-08-30T14:00:02Z",
    });
    const system = event({
      id: "system",
      role: "system",
      text: "Internal",
      observedAt: "2026-08-30T14:00:03Z",
    });
    const reasoning = event({
      id: "reasoning",
      role: "reasoning",
      text: "Hidden",
      observedAt: "2026-08-30T14:00:04Z",
    });
    const memory = event({
      id: "memory",
      eventType: "Decision",
      role: "assistant",
      text: "Remember",
      observedAt: "2026-08-30T14:00:05Z",
    });

    expect([prompt, response, tool, system, reasoning, memory].map(isSessionReaderEvent)).toEqual([
      true,
      true,
      true,
      false,
      false,
      false,
    ]);
    expect(sessionConversationRole(prompt)).toBe("user");
    expect(sessionConversationRole(response)).toBe("assistant");
    expect(sessionConversationRole(tool)).toBeNull();
  });

  it("matches quoted and cross-field terms while retaining the complete matching turn", () => {
    const matchingTurn = {
      id: "turn-1",
      events: [
        event({
          id: "prompt",
          role: "user",
          text: "Please inspect the config",
          observedAt: "2026-08-30T14:00:00Z",
        }),
        event({
          id: "tool",
          role: "tool",
          toolName: "Read",
          toolInputJson: '{"file_path":"frontend/vite.config.ts"}',
          observedAt: "2026-08-30T14:00:01Z",
        }),
        event({
          id: "response",
          role: "assistant",
          text: "A response kept for context",
          observedAt: "2026-08-30T14:00:02Z",
        }),
        event({
          id: "memory",
          eventType: "Decision",
          role: "assistant",
          text: "Decision text is not a transcript search field",
          observedAt: "2026-08-30T14:00:03Z",
        }),
      ],
    };
    const otherTurn = {
      id: "turn-2",
      events: [
        event({
          id: "other",
          role: "assistant",
          text: "Unrelated",
          observedAt: "2026-08-30T14:01:00Z",
        }),
      ],
    };

    expect(filterSessionTranscriptTurns([matchingTurn, otherTurn], 'read "vite.config"')).toEqual([
      matchingTurn,
    ]);
    expect(filterSessionTranscriptTurns([matchingTurn, otherTurn], "context")).toEqual([
      matchingTurn,
    ]);
    expect(filterSessionTranscriptTurns([matchingTurn, otherTurn], "decision text")).toEqual([]);
  });
});
