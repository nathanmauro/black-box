import type { AgentEvent } from "../api";
import { normalizeToolName, presentationOf } from "./registry";

/**
 * Kind-mark vocabulary for collapsed chatter rows (spec §4.3): the tone
 * system's tool-semantic vocabulary rendered as ink-only marks. `mem` is the
 * mark spelling of the `memory` tone; `·` is the generic/unknown mark.
 * Rule order matters — e.g. TodoWrite must resolve to `plan` before the
 * `write` rule can claim it, searchSessions to `mem` before `net`.
 */
const MARK_RULES: Array<{ mark: string; match: RegExp }> = [
  { mark: "run", match: /bash|shell|terminal|exec|command/ },
  { mark: "plan", match: /plan|todo/ },
  { mark: "mem", match: /memor|recall|remember|session/ },
  { mark: "net", match: /fetch|search|web|http|url|browse|crawl/ },
  { mark: "edit", match: /edit|patch/ },
  { mark: "write", match: /write|create/ },
  { mark: "read", match: /read|glob|grep|view|open|(^|_)cat($|_)|(^|_)ls($|_)/ },
  { mark: "ask", match: /^ask|question|prompt/ },
];

export const GENERIC_MARK = "·";

export type KindMark = { label: string; error: boolean };

export function kindMarkLabel(toolName: string | null | undefined): string {
  const name = normalizeToolName(toolName);
  if (!name) return GENERIC_MARK;
  for (const rule of MARK_RULES) {
    if (rule.match.test(name)) return rule.mark;
  }
  return GENERIC_MARK;
}

/**
 * Mark label + failure flag for one event. The failure flag reads the cached
 * presentation (WeakMap in registry.ts), so payload parsing happens once per
 * row no matter how often the row re-renders (spec §15 budget).
 */
export function kindMarkOf(event: AgentEvent): KindMark {
  return {
    label: kindMarkLabel(event.toolName),
    error: presentationOf(event).kindPill.tone === "error",
  };
}
