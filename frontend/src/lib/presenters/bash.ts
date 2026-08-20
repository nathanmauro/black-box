import type { AgentEvent } from "../api";
import { parseJsonObject, parseToolResult } from "../payload";
import { outputLooksFailed } from "./failure";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

export function bashPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const command = firstString(input, ["command", "cmd", "script"]);
  if (!command) return genericPresenter(event);
  const cwd = firstString(input, ["cwd", "workdir"]);
  const result = normalizeResult(parseToolResult(event.toolOutputJson));

  const lines = command.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const first = lines.find((line) => !line.startsWith("#")) ?? lines[0] ?? command.trim();
  const suffix = lines.length > 1 ? ` +${lines.length - 1} lines` : "";
  const failed = (result.exitCode !== null && result.exitCode !== 0) || outputLooksFailed(event.toolOutputJson);

  return {
    kindPill: { label: "Bash", tone: failed ? "error" : "run" },
    headline: [{ kind: "code", text: `${first.slice(0, 180)}${suffix}` }],
    blocks: [{ kind: "bash", command, cwd: cwd ?? null, output: result.output, exitCode: result.exitCode, wallTime: result.wallTime }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [],
  };
}

function firstString(record: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "string" && value.trim()) return value;
  }
  return null;
}

function normalizeResult(value: unknown): { output: string | null; exitCode: number | null; wallTime: string | null } {
  if (typeof value === "string") return { output: value, exitCode: null, wallTime: null };
  if (value && typeof value === "object" && !Array.isArray(value)) {
    const record = value as Record<string, unknown>;
    const output = ["output", "stdout", "result", "content"]
      .map((key) => record[key])
      .find((candidate): candidate is string => typeof candidate === "string") ?? null;
    const exitRaw = record.exit_code ?? record.exitCode ?? record.code;
    const exitCode = typeof exitRaw === "number"
      ? exitRaw
      : typeof exitRaw === "string" && exitRaw.trim() !== "" && Number.isFinite(Number(exitRaw)) ? Number(exitRaw) : null;
    const wallRaw = record.wall_time ?? record.wall_time_seconds ?? record.wallTime;
    const wallTime = typeof wallRaw === "string" ? wallRaw : typeof wallRaw === "number" ? String(wallRaw) : null;
    return { output, exitCode, wallTime };
  }
  return { output: null, exitCode: null, wallTime: null };
}
