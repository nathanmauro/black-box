import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { langForPath, readPresenter } from "./read";
import { headlineText } from "./registry";

function readEvent(overrides: Partial<AgentEvent>): AgentEvent {
  return {
    id: "evt-read",
    sessionId: "ses",
    source: "claude",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName: "Read",
    observedAt: "2026-07-28T12:00:00Z",
    ...overrides,
  };
}

describe("readPresenter", () => {
  it("headlines the home-shortened path as a file link with a line range", () => {
    const event = readEvent({
      toolInputJson: JSON.stringify({ file_path: "/Users/nathan/Developer/proj/x/a.ts", offset: 10, limit: 40 }),
      toolOutputJson: JSON.stringify({ output: "const x = 1;" }),
    });
    const presentation = readPresenter(event);
    expect(headlineText(presentation)).toBe("~/Developer/proj/x/a.ts:10–50");
    expect(presentation.headline[0]).toMatchObject({ kind: "fileLink", file: { path: "/Users/nathan/Developer/proj/x/a.ts", line: 10 } });
    expect(presentation.refs).toEqual([{ path: "/Users/nathan/Developer/proj/x/a.ts", line: 10 }]);
    expect(presentation.kindPill).toEqual({ label: "Read", tone: "read" });
  });

  it("wraps content in a code block with a size label and inferred language", () => {
    const event = readEvent({
      toolInputJson: JSON.stringify({ file_path: "/tmp/app.py" }),
      toolOutputJson: JSON.stringify({ output: "print('hi')" }),
    });
    const [block] = readPresenter(event).blocks;
    expect(block).toMatchObject({ kind: "code", lang: "python", text: "print('hi')", label: "Content (11 chars)" });
  });

  it("emits no blocks when there is no output yet (PreToolUse)", () => {
    const event = readEvent({ eventType: "PreToolUse", toolInputJson: JSON.stringify({ file_path: "/tmp/a.txt" }) });
    expect(readPresenter(event).blocks).toEqual([]);
  });

  it("falls back to generic without a path", () => {
    expect(readPresenter(readEvent({ toolInputJson: "{}" })).blocks[0].kind).toBe("fallback");
  });
});

describe("langForPath", () => {
  it("maps common extensions and returns null for unknown ones", () => {
    expect(langForPath("/a/b.tsx")).toBe("tsx");
    expect(langForPath("/a/b.java")).toBe("java");
    expect(langForPath("/a/b.unknownext")).toBeNull();
  });
});
