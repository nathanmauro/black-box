import type { DiffLine, Hunk } from "./diff";

export type PatchOp = "update" | "add" | "delete";

export type PatchFileStub = { op: PatchOp; path: string; movedTo: string | null };

export type PatchFile = PatchFileStub & { hunks: Hunk[] };

const FILE_HEADER = /^\*\*\*\s+(Update|Add|Delete)\s+File:\s*(.+?)\s*$/;
const MOVE_HEADER = /^\*\*\*\s+Move to:\s*(.+?)\s*$/;
const PATCH_FENCE = /^\*\*\*\s+(Begin|End)\s+Patch/;

export function patchFileStubs(command: string): PatchFileStub[] {
  const stubs: PatchFileStub[] = [];
  const lines = command.split(/\r?\n/);
  lines.forEach((line, index) => {
    const header = FILE_HEADER.exec(line.trim());
    if (!header) return;
    const move = MOVE_HEADER.exec(lines[index + 1]?.trim() ?? "");
    stubs.push({
      op: header[1].toLowerCase() as PatchOp,
      path: header[2],
      movedTo: move ? move[1] : null,
    });
  });
  return stubs;
}

export function parseApplyPatch(command: string): PatchFile[] | null {
  if (!/\*\*\*\s+Begin Patch/.test(command) && !FILE_HEADER.test(command.split(/\r?\n/, 1)[0] ?? "")) return null;
  const files: PatchFile[] = [];
  let current: PatchFile | null = null;
  let hunk: Hunk | null = null;

  for (const rawLine of command.split(/\r?\n/)) {
    const trimmed = rawLine.trim();
    if (PATCH_FENCE.test(trimmed)) continue;

    const header = FILE_HEADER.exec(trimmed);
    if (header) {
      current = { op: header[1].toLowerCase() as PatchOp, path: header[2], movedTo: null, hunks: [] };
      files.push(current);
      hunk = null;
      continue;
    }

    const move = MOVE_HEADER.exec(trimmed);
    if (move && current) {
      current.movedTo = move[1];
      continue;
    }

    if (!current) {
      if (!trimmed) continue;
      return null; // body content before any file header — not a patch we understand
    }

    if (trimmed.startsWith("@@")) {
      hunk = { lines: [] };
      current.hunks.push(hunk);
      continue;
    }

    const kind: DiffLine["kind"] = rawLine.startsWith("+") ? "add" : rawLine.startsWith("-") ? "del" : "context";
    const text = rawLine.startsWith("+") || rawLine.startsWith("-") || rawLine.startsWith(" ") ? rawLine.slice(1) : rawLine;
    if (!hunk) {
      hunk = { lines: [] };
      current.hunks.push(hunk);
    }
    hunk.lines.push({ kind, text, oldLine: null, newLine: null });
  }

  return files.length ? files : null;
}
