import type { RecallResult } from "./api";

type RememberedRecall = { key: string; result: RecallResult; completedAt: Date };

// One completed full recall, in memory only, so Back from Browse can show it again. A new tab or
// reload starts empty; suggestions, failures, and superseded responses are never stored.
let remembered: RememberedRecall | null = null;

export function rememberRecall(key: string, result: RecallResult, completedAt = new Date()) {
  remembered = { key, result, completedAt };
}

export function recallFor(key: string): RememberedRecall | null {
  return remembered?.key === key ? remembered : null;
}

export function forgetRecall() {
  remembered = null;
}
