import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { editPresenter } from "./edit";
import { writePresenter } from "./write";
import { headlineText } from "./registry";

function toolEvent(toolName: string, inputJson: string): AgentEvent {
  return {
    id: `evt-${toolName}`,
    sessionId: "ses",
    source: "claude",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName,
    toolInputJson: inputJson,
    observedAt: "2026-07-28T12:00:00Z",
  };
}

describe("editPresenter", () => {
  it("emits a lazy diff block carrying the raw old and new strings", () => {
    const event = toolEvent("Edit", JSON.stringify({
      file_path: "/Users/nathan/Developer/proj/x/a.ts",
      old_string: "const a = 1;",
      new_string: "const a = 2;",
    }));
    const presentation = editPresenter(event);
    expect(presentation.kindPill).toEqual({ label: "Edit", tone: "edit" });
    expect(headlineText(presentation)).toBe("~/Developer/proj/x/a.ts");
    expect(presentation.blocks).toEqual([{
      kind: "diff",
      file: { path: "/Users/nathan/Developer/proj/x/a.ts" },
      oldText: "const a = 1;",
      newText: "const a = 2;",
      label: "Diff (12 → 12 chars)",
    }]);
    expect(presentation.refs).toEqual([{ path: "/Users/nathan/Developer/proj/x/a.ts" }]);
  });

  it("falls back to generic when old or new strings are missing", () => {
    const event = toolEvent("Edit", JSON.stringify({ file_path: "/tmp/a.ts" }));
    expect(editPresenter(event).blocks[0].kind).toBe("fallback");
  });
});

describe("writePresenter", () => {
  it("emits an all-additions diff block (empty oldText)", () => {
    const event = toolEvent("Write", JSON.stringify({ file_path: "/tmp/new.txt", content: "hello\nworld" }));
    const presentation = writePresenter(event);
    expect(presentation.kindPill).toEqual({ label: "Write", tone: "write" });
    expect(presentation.blocks).toEqual([{
      kind: "diff",
      file: { path: "/tmp/new.txt" },
      oldText: "",
      newText: "hello\nworld",
      label: "New file (11 chars)",
    }]);
  });

  it("falls back to generic without content", () => {
    const event = toolEvent("Write", JSON.stringify({ file_path: "/tmp/new.txt" }));
    expect(writePresenter(event).blocks[0].kind).toBe("fallback");
  });
});
