import { describe, expect, it } from "vitest";
import type { AgentEvent } from "./api";
import {
  filterSessionTranscriptTurns,
  isSessionReaderEvent,
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

describe("session transcript reading and search", () => {
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
