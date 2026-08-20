import type { PatchFileStub } from "../patch";
import type { AgentEvent } from "../api";

export type Tone = "neutral" | "run" | "read" | "edit" | "write" | "net" | "plan" | "memory" | "error";

export type FileRef = { path: string; line?: number | null };

export type InlineSpan =
  | { kind: "text"; text: string }
  | { kind: "code"; text: string }
  | { kind: "fileLink"; label: string; file: FileRef }
  | { kind: "url"; href: string; label: string };

export type PlanStep = { step: string; status: string };

export type DetailBlock =
  | { kind: "diff"; file: FileRef; oldText: string; newText: string; label: string }
  | { kind: "patch"; command: string; files: PatchFileStub[] }
  | { kind: "bash"; command: string; cwd: string | null; output: string | null; exitCode: number | null; wallTime: string | null }
  | { kind: "plan"; explanation: string | null; steps: PlanStep[] }
  | { kind: "code"; lang: string | null; text: string; file: FileRef | null; label: string }
  | { kind: "markdown"; text: string }
  | { kind: "json"; value: unknown; label: string }
  | { kind: "text"; text: string; label: string }
  | { kind: "fallback"; toolName: string | null; inputJson: string | null; outputJson: string | null };

export type Presentation = {
  kindPill: { label: string; tone: Tone };
  headline: InlineSpan[];
  blocks: DetailBlock[];
  sizes: { inputChars: number; outputChars: number };
  refs: FileRef[];
};

export type Presenter = (event: AgentEvent) => Presentation;
