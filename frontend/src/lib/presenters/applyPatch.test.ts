import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { applyPatchPresenter } from "./applyPatch";
import { headlineText } from "./registry";

function patchEvent(command: string): AgentEvent {
  return {
    id: "evt-patch",
    sessionId: "ses",
    source: "codex",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName: "apply_patch",
    toolInputJson: JSON.stringify({ command }),
    observedAt: "2026-07-28T12:00:00Z",
  };
}

describe("applyPatchPresenter", () => {
  it("headlines the first patched file and counts the rest", () => {
    const command = "*** Begin Patch\n*** Update File: /Users/nathan/Developer/proj/x/a.ts\n@@\n+x\n*** Add File: /tmp/b.txt\n+y\n*** End Patch";
    const presentation = applyPatchPresenter(patchEvent(command));
    expect(presentation.kindPill).toEqual({ label: "Patch", tone: "write" });
    expect(headlineText(presentation)).toBe("Patch ~/Developer/proj/x/a.ts +1 more");
    expect(presentation.refs).toEqual([{ path: "/Users/nathan/Developer/proj/x/a.ts" }, { path: "/tmp/b.txt" }]);
    const [block] = presentation.blocks;
    expect(block.kind).toBe("patch");
    if (block.kind === "patch") {
      expect(block.command).toBe(command);
      expect(block.files.map((stub) => stub.op)).toEqual(["update", "add"]);
    }
  });

  it("falls back to generic when the command is not patch-shaped", () => {
    expect(applyPatchPresenter(patchEvent("echo not-a-patch")).blocks[0].kind).toBe("fallback");
  });
});
