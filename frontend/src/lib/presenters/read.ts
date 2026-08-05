import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { parseJsonObject, payloadText } from "../payload";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

const LANGS: Record<string, string> = {
  ts: "typescript", tsx: "tsx", js: "javascript", jsx: "jsx", mjs: "javascript",
  java: "java", py: "python", rb: "ruby", go: "go", rs: "rust", sh: "shell", zsh: "shell",
  css: "css", html: "html", json: "json", yml: "yaml", yaml: "yaml", md: "markdown",
  sql: "sql", xml: "xml", toml: "toml",
};

export function langForPath(path: string): string | null {
  const extension = /\.([a-z0-9]+)$/i.exec(path)?.[1]?.toLowerCase();
  return extension ? LANGS[extension] ?? null : null;
}

export function readPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const pathValue = input.file_path ?? input.filePath ?? input.path;
  if (typeof pathValue !== "string" || !pathValue.trim()) return genericPresenter(event);
  const offset = typeof input.offset === "number" ? input.offset : null;
  const limit = typeof input.limit === "number" ? input.limit : null;
  const file = { path: pathValue, line: offset };
  const content = payloadText(event.toolOutputJson);
  const range = offset !== null ? `:${offset}${limit !== null ? `–${offset + limit}` : ""}` : "";

  return {
    kindPill: { label: "Read", tone: "read" },
    headline: [{ kind: "fileLink", label: `${truncatePath(pathValue)}${range}`, file }],
    blocks: content
      ? [{ kind: "code", lang: langForPath(pathValue), text: content, file, label: `Content (${content.length.toLocaleString("en-US")} chars)` }]
      : [],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [file],
  };
}
