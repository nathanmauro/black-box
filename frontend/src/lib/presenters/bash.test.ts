import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { bashPresenter } from "./bash";
import { headlineText } from "./registry";

function bashEvent(overrides: Partial<AgentEvent>): AgentEvent {
  return {
    id: "evt-bash",
    sessionId: "ses",
    source: "claude",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName: "Bash",
    observedAt: "2026-07-28T12:00:00Z",
    ...overrides,
  };
}

describe("bashPresenter", () => {
  it("headlines the first non-comment line with a +N lines suffix", () => {
    const event = bashEvent({
      toolInputJson: JSON.stringify({
        command: "# setup\nnpm test\nnpm run build",
        cwd: "/tmp/proj",
      }),
    });
    const presentation = bashPresenter(event);
    expect(headlineText(presentation)).toBe("npm test +2 lines");
    expect(presentation.kindPill).toEqual({ label: "Bash", tone: "run" });
  });

  it("builds a bash block from the Codex structured result", () => {
    const event = bashEvent({
      toolInputJson: JSON.stringify({ command: "ls", cwd: "/tmp" }),
      toolOutputJson: JSON.stringify("Exit code: 0\nWall time: 0.1 seconds\nOutput:\nfile.txt"),
    });
    const [block] = bashPresenter(event).blocks;
    expect(block).toEqual({
      kind: "bash",
      command: "ls",
      cwd: "/tmp",
      output: "file.txt",
      exitCode: 0,
      wallTime: "0.1 seconds",
    });
  });

  it("turns non-zero exits into the error tone", () => {
    const event = bashEvent({
      toolInputJson: JSON.stringify({ command: "false" }),
      toolOutputJson: JSON.stringify({ exit_code: 1, output: "boom" }),
    });
    expect(bashPresenter(event).kindPill.tone).toBe("error");
  });

  it("falls back to generic when there is no command at all", () => {
    const event = bashEvent({ toolInputJson: JSON.stringify({ description: "no command key" }) });
    expect(bashPresenter(event).blocks[0].kind).toBe("fallback");
  });
});
