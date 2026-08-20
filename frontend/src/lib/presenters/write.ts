import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { parseJsonObject } from "../payload";
import { outputLooksFailed } from "./failure";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

export function writePresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const pathValue = input.file_path ?? input.filePath;
  const content = input.content ?? input.contents;
  if (typeof pathValue !== "string" || typeof content !== "string") return genericPresenter(event);
  const file = { path: pathValue };
  return {
    kindPill: { label: "Write", tone: outputLooksFailed(event.toolOutputJson) ? "error" : "write" },
    headline: [{ kind: "fileLink", label: truncatePath(pathValue), file }],
    blocks: [{ kind: "diff", file, oldText: "", newText: content, label: `New file (${content.length} chars)` }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [file],
  };
}
