import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import bashFixtures from "./__fixtures__/bash.json";
import editFixtures from "./__fixtures__/edit.json";
import writeFixtures from "./__fixtures__/write.json";
import readFixtures from "./__fixtures__/read.json";
import patchFixtures from "./__fixtures__/apply_patch.json";
import { headlineText, normalizeToolName, presentationOf, presenterFor } from "./registry";
import { genericPresenter } from "./generic";

export function fixtureEvent(
  row: {
    toolName: string;
    eventType: string;
    toolInputJson: string | null;
    toolOutputJson: string | null;
  },
  index: number,
): AgentEvent {
  return {
    id: `fixture-${row.toolName}-${index}`,
    sessionId: "fixture-session",
    source: "claude",
    clientSessionId: "fixture-client",
    eventType: row.eventType,
    toolName: row.toolName,
    toolInputJson: row.toolInputJson,
    toolOutputJson: row.toolOutputJson,
    observedAt: "2026-07-28T12:00:00Z",
  };
}

describe("normalizeToolName", () => {
  it("lowercases and strips mcp prefixes", () => {
    expect(normalizeToolName("Bash")).toBe("bash");
    expect(normalizeToolName("mcp__sba-agentic__captureDecision")).toBe("capturedecision");
    expect(normalizeToolName(null)).toBe("");
  });
});

describe("presenterFor", () => {
  it("resolves unknown tools to the generic presenter", () => {
    expect(presenterFor("SomeUnknownTool")).toBe(genericPresenter);
    expect(presenterFor(null)).toBe(genericPresenter);
  });
});

describe("presentationOf", () => {
  it("caches per event object", () => {
    const event = fixtureEvent(
      { toolName: "Nope", eventType: "PostToolUse", toolInputJson: "{}", toolOutputJson: null },
      0,
    );
    expect(presentationOf(event)).toBe(presentationOf(event));
  });

  it("never throws across the entire golden corpus and always reports sizes", () => {
    const corpus = [
      ...bashFixtures,
      ...editFixtures,
      ...writeFixtures,
      ...readFixtures,
      ...patchFixtures,
    ];
    expect(corpus.length).toBeGreaterThan(0);
    corpus.forEach((row, index) => {
      const event = fixtureEvent(row, index);
      const presentation = presentationOf(event);
      expect(presentation.blocks.length).toBeGreaterThanOrEqual(0);
      expect(presentation.sizes.inputChars).toBe(row.toolInputJson?.length ?? 0);
      expect(presentation.sizes.outputChars).toBe(row.toolOutputJson?.length ?? 0);
    });
  });
});

describe("genericPresenter", () => {
  it("emits a single fallback block and an empty headline", () => {
    const event = fixtureEvent(
      {
        toolName: "Mystery",
        eventType: "PostToolUse",
        toolInputJson: '{"a":1}',
        toolOutputJson: null,
      },
      0,
    );
    const presentation = genericPresenter(event);
    expect(presentation.headline).toEqual([]);
    expect(presentation.blocks).toEqual([
      { kind: "fallback", toolName: "Mystery", inputJson: '{"a":1}', outputJson: null },
    ]);
    expect(headlineText(presentation)).toBe("");
  });
});

import { registerPresenter } from "./registry";

describe("presentationOf error containment", () => {
  it("degrades a throwing presenter to the generic fallback", () => {
    registerPresenter("explosive", () => {
      throw new Error("presenter bug");
    });
    const event = fixtureEvent(
      {
        toolName: "explosive",
        eventType: "PostToolUse",
        toolInputJson: "{}",
        toolOutputJson: null,
      },
      0,
    );
    expect(presentationOf(event).blocks[0].kind).toBe("fallback");
  });
});
