import type { AgentEvent } from "../api";
import type { Presentation } from "./types";

export function genericPresenter(event: AgentEvent): Presentation {
  return {
    kindPill: { label: event.toolName || event.eventType || "Event", tone: "neutral" },
    headline: [],
    blocks: [{
      kind: "fallback",
      toolName: event.toolName ?? null,
      inputJson: event.toolInputJson ?? null,
      outputJson: event.toolOutputJson ?? null,
    }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [],
  };
}
