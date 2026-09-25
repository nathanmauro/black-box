import type { AgentEvent } from "../api";
import { applyPatchPresenter } from "./applyPatch";
import { bashPresenter } from "./bash";
import { editPresenter } from "./edit";
import { genericPresenter } from "./generic";
import { readPresenter } from "./read";
import type { Presentation, Presenter } from "./types";
import { writePresenter } from "./write";

const REGISTRY: Record<string, Presenter> = {
  bash: bashPresenter,
  shell: bashPresenter,
  read: readPresenter,
  edit: editPresenter,
  write: writePresenter,
  apply_patch: applyPatchPresenter,
};

export function normalizeToolName(toolName: string | null | undefined): string {
  const raw = String(toolName || "")
    .trim()
    .toLowerCase();
  const mcp = /^mcp__.+__(.+)$/.exec(raw);
  return mcp ? mcp[1] : raw;
}

export function presenterFor(toolName: string | null | undefined, _eventType?: string): Presenter {
  return REGISTRY[normalizeToolName(toolName)] ?? genericPresenter;
}

const presentationCache = new WeakMap<AgentEvent, Presentation>();

export function presentationOf(event: AgentEvent): Presentation {
  const hit = presentationCache.get(event);
  if (hit) return hit;
  let presentation: Presentation;
  try {
    presentation = presenterFor(event.toolName, event.eventType)(event);
  } catch {
    presentation = genericPresenter(event);
  }
  presentationCache.set(event, presentation);
  return presentation;
}

export function headlineText(presentation: Presentation): string {
  return presentation.headline
    .map((span) => (span.kind === "fileLink" || span.kind === "url" ? span.label : span.text))
    .join("")
    .trim();
}

export function registerPresenter(name: string, presenter: Presenter): void {
  REGISTRY[name] = presenter;
}
