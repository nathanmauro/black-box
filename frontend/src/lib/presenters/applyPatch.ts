import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { patchFileStubs } from "../patch";
import { parseJsonObject } from "../payload";
import { outputLooksFailed } from "./failure";
import { genericPresenter } from "./generic";
import type { InlineSpan, Presentation } from "./types";

export function applyPatchPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const commandValue = input.command ?? input.input ?? input.patch;
  if (
    typeof commandValue !== "string" ||
    !/\*\*\*\s+(Begin Patch|Update File|Add File|Delete File)/.test(commandValue)
  ) {
    return genericPresenter(event);
  }
  const files = patchFileStubs(commandValue);
  const first = files[0];
  const headline: InlineSpan[] = first
    ? [
        { kind: "text", text: "Patch " },
        { kind: "fileLink", label: truncatePath(first.path), file: { path: first.path } },
        ...(files.length > 1
          ? [{ kind: "text", text: ` +${files.length - 1} more` } satisfies InlineSpan]
          : []),
      ]
    : [{ kind: "text", text: "Patch" }];

  return {
    kindPill: { label: "Patch", tone: outputLooksFailed(event.toolOutputJson) ? "error" : "write" },
    headline,
    blocks: [{ kind: "patch", command: commandValue, files }],
    sizes: {
      inputChars: event.toolInputJson?.length ?? 0,
      outputChars: event.toolOutputJson?.length ?? 0,
    },
    refs: files.map((stub) => ({ path: stub.path })),
  };
}
