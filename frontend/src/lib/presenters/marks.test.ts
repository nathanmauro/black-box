import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { GENERIC_MARK, kindMarkLabel, kindMarkOf } from "./marks";
import { presentationOf } from "./registry";

function toolEvent(id: string, toolName: string | null, inputJson: string | null, outputJson: string | null): AgentEvent {
  return {
    id,
    sessionId: "ses",
    source: "claude",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName,
    toolInputJson: inputJson,
    toolOutputJson: outputJson,
    observedAt: "2026-08-20T12:00:00Z",
  };
}

describe("kindMarkLabel", () => {
  it("maps tool semantics onto the mark vocabulary", () => {
    expect(kindMarkLabel("Bash")).toBe("run");
    expect(kindMarkLabel("shell")).toBe("run");
    expect(kindMarkLabel("Read")).toBe("read");
    expect(kindMarkLabel("Glob")).toBe("read");
    expect(kindMarkLabel("Grep")).toBe("read");
    expect(kindMarkLabel("Edit")).toBe("edit");
    expect(kindMarkLabel("NotebookEdit")).toBe("edit");
    expect(kindMarkLabel("apply_patch")).toBe("edit");
    expect(kindMarkLabel("Write")).toBe("write");
    expect(kindMarkLabel("WebFetch")).toBe("net");
    expect(kindMarkLabel("WebSearch")).toBe("net");
    expect(kindMarkLabel("TodoWrite")).toBe("plan");
    expect(kindMarkLabel("ExitPlanMode")).toBe("plan");
    expect(kindMarkLabel("update_plan")).toBe("plan");
    expect(kindMarkLabel("AskUserQuestion")).toBe("ask");
  });

  it("maps memory and recall tools to mem, including session search", () => {
    expect(kindMarkLabel("mcp__memory__memory_store")).toBe("mem");
    expect(kindMarkLabel("mcp__sba-agentic__recallContext")).toBe("mem");
    expect(kindMarkLabel("mcp__sba-agentic__searchSessions")).toBe("mem");
  });

  it("marks unknown or missing tools with the generic dot", () => {
    expect(kindMarkLabel("Task")).toBe(GENERIC_MARK);
    expect(kindMarkLabel("SendMessage")).toBe(GENERIC_MARK);
    expect(kindMarkLabel(null)).toBe(GENERIC_MARK);
    expect(kindMarkLabel("")).toBe(GENERIC_MARK);
  });
});

describe("kindMarkOf failure tone (D3)", () => {
  it("keeps a clean bash exit quiet and turns a nonzero exit red", () => {
    const ok = toolEvent("ok", "Bash", '{"command":"true"}', '{"exit_code":0,"output":""}');
    const bad = toolEvent("bad", "Bash", '{"command":"false"}', '{"exit_code":1,"output":"boom"}');
    expect(kindMarkOf(ok)).toEqual({ label: "run", error: false });
    expect(kindMarkOf(bad)).toEqual({ label: "run", error: true });
  });

  it("derives error from is_error:true beyond bash, and only from true", () => {
    const bad = toolEvent("read-bad", "Read", '{"file_path":"/tmp/x"}', '{"is_error":true}');
    const ok = toolEvent("read-ok", "Read", '{"file_path":"/tmp/x"}', '{"is_error":false,"content":"hi"}');
    expect(kindMarkOf(bad).error).toBe(true);
    expect(kindMarkOf(ok).error).toBe(false);
  });

  it("flags unknown tools with error-shaped output but never colors parse failures", () => {
    const unknownBad = toolEvent("u-bad", "MysteryTool", "{}", '{"is_error":true}');
    const malformed = toolEvent("mal", "MysteryTool", "{}", '{"is_error": tru');
    const unknownQuiet = toolEvent("u-ok", "MysteryTool", "{}", '{"anything":"else"}');
    expect(kindMarkOf(unknownBad).error).toBe(true);
    expect(kindMarkOf(malformed).error).toBe(false);
    expect(kindMarkOf(unknownQuiet).error).toBe(false);
  });

  it("computes the failure tone once per row via the presentation cache", () => {
    const event = toolEvent("cached", "Bash", '{"command":"false"}', '{"exit_code":1}');
    const first = presentationOf(event);
    kindMarkOf(event);
    expect(presentationOf(event)).toBe(first);
  });

  // §15 budget: no measurable jank at 500 rows. Cold derivation parses each
  // payload once; warm derivation is a WeakMap hit. The bound is deliberately
  // loose (CI-safe) — locally this runs in low single-digit milliseconds.
  it("derives marks for 500 rows well inside the frame budget", () => {
    const rows = Array.from({ length: 500 }, (_, index) =>
      toolEvent(`perf-${index}`, index % 2 ? "Bash" : "Read", '{"command":"npm test","file_path":"/tmp/x"}', '{"exit_code":1,"output":"x"}'),
    );
    const cold = performance.now();
    rows.forEach((row) => kindMarkOf(row));
    const coldMs = performance.now() - cold;
    const warm = performance.now();
    rows.forEach((row) => kindMarkOf(row));
    const warmMs = performance.now() - warm;
    expect(coldMs).toBeLessThan(250);
    expect(warmMs).toBeLessThan(50);
  });
});
