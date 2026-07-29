import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { parseJsonObject } from "../payload";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

export function editPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const pathValue = input.file_path ?? input.filePath;
  const oldText = input.old_string;
  const newText = input.new_string;
  if (typeof pathValue !== "string" || typeof oldText !== "string" || typeof newText !== "string") {
    return genericPresenter(event);
  }
  const file = { path: pathValue };
  return {
    kindPill: { label: "Edit", tone: "write" },
    headline: [{ kind: "fileLink", label: truncatePath(pathValue), file }],
    blocks: [{ kind: "diff", file, oldText, newText, label: `Diff (${oldText.length} → ${newText.length} chars)` }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [file],
  };
}
