export type DiffLineKind = "context" | "add" | "del";

export type DiffLine = {
  kind: DiffLineKind;
  text: string;
  oldLine: number | null;
  newLine: number | null;
};

export type Hunk = { lines: DiffLine[] };

/** Above this many DP cells the diff degrades to whole-block replace (perf guard). */
const LCS_CELL_BUDGET = 2_000_000;
const CACHE_MAX = 200;

export function diffLines(oldText: string, newText: string, context = 3): Hunk[] {
  if (oldText === newText) return [];
  const oldLines = splitLines(oldText);
  const newLines = splitLines(newText);

  let prefix = 0;
  while (
    prefix < oldLines.length &&
    prefix < newLines.length &&
    oldLines[prefix] === newLines[prefix]
  )
    prefix += 1;
  let suffix = 0;
  while (
    suffix < oldLines.length - prefix &&
    suffix < newLines.length - prefix &&
    oldLines[oldLines.length - 1 - suffix] === newLines[newLines.length - 1 - suffix]
  )
    suffix += 1;

  const ops: DiffLine[] = [];
  for (let index = 0; index < prefix; index += 1) {
    ops.push({ kind: "context", text: oldLines[index], oldLine: index + 1, newLine: index + 1 });
  }
  ops.push(
    ...middleOps(
      oldLines.slice(prefix, oldLines.length - suffix),
      newLines.slice(prefix, newLines.length - suffix),
      prefix,
    ),
  );
  for (let index = 0; index < suffix; index += 1) {
    const oldLine = oldLines.length - suffix + index + 1;
    const newLine = newLines.length - suffix + index + 1;
    ops.push({ kind: "context", text: oldLines[oldLine - 1], oldLine, newLine });
  }
  return groupIntoHunks(ops, context);
}

export function allAdditions(text: string): Hunk[] {
  const lines = splitLines(text);
  if (!lines.length) return [];
  return [
    {
      lines: lines.map((line, index) => ({
        kind: "add" as const,
        text: line,
        oldLine: null,
        newLine: index + 1,
      })),
    },
  ];
}

const cache = new Map<string, Hunk[]>();

export function memoizedDiffLines(key: string, oldText: string, newText: string): Hunk[] {
  const hit = cache.get(key);
  if (hit) return hit;
  const hunks = diffLines(oldText, newText);
  if (cache.size >= CACHE_MAX) {
    const oldest = cache.keys().next().value;
    if (oldest !== undefined) cache.delete(oldest);
  }
  cache.set(key, hunks);
  return hunks;
}

function splitLines(text: string): string[] {
  if (text === "") return [];
  return text.split(/\r?\n/);
}

function middleOps(oldMid: string[], newMid: string[], offset: number): DiffLine[] {
  const ops: DiffLine[] = [];
  if (!oldMid.length && !newMid.length) return ops;
  const overBudget = oldMid.length * newMid.length > LCS_CELL_BUDGET;
  if (overBudget || !oldMid.length || !newMid.length) {
    oldMid.forEach((text, index) =>
      ops.push({ kind: "del", text, oldLine: offset + index + 1, newLine: null }),
    );
    newMid.forEach((text, index) =>
      ops.push({ kind: "add", text, oldLine: null, newLine: offset + index + 1 }),
    );
    return ops;
  }

  const cols = newMid.length + 1;
  const table = new Uint32Array((oldMid.length + 1) * cols);
  for (let row = oldMid.length - 1; row >= 0; row -= 1) {
    for (let col = newMid.length - 1; col >= 0; col -= 1) {
      table[row * cols + col] =
        oldMid[row] === newMid[col]
          ? table[(row + 1) * cols + col + 1] + 1
          : Math.max(table[(row + 1) * cols + col], table[row * cols + col + 1]);
    }
  }

  let row = 0;
  let col = 0;
  while (row < oldMid.length && col < newMid.length) {
    if (oldMid[row] === newMid[col]) {
      ops.push({
        kind: "context",
        text: oldMid[row],
        oldLine: offset + row + 1,
        newLine: offset + col + 1,
      });
      row += 1;
      col += 1;
    } else if (table[(row + 1) * cols + col] >= table[row * cols + col + 1]) {
      ops.push({ kind: "del", text: oldMid[row], oldLine: offset + row + 1, newLine: null });
      row += 1;
    } else {
      ops.push({ kind: "add", text: newMid[col], oldLine: null, newLine: offset + col + 1 });
      col += 1;
    }
  }
  while (row < oldMid.length) {
    ops.push({ kind: "del", text: oldMid[row], oldLine: offset + row + 1, newLine: null });
    row += 1;
  }
  while (col < newMid.length) {
    ops.push({ kind: "add", text: newMid[col], oldLine: null, newLine: offset + col + 1 });
    col += 1;
  }
  return ops;
}

function groupIntoHunks(ops: DiffLine[], context: number): Hunk[] {
  if (!ops.some((op) => op.kind !== "context")) return [];
  const keep = new Array<boolean>(ops.length).fill(false);
  ops.forEach((op, index) => {
    if (op.kind === "context") return;
    const from = Math.max(0, index - context);
    const to = Math.min(ops.length - 1, index + context);
    for (let mark = from; mark <= to; mark += 1) keep[mark] = true;
  });
  const hunks: Hunk[] = [];
  let current: Hunk | null = null;
  ops.forEach((op, index) => {
    if (!keep[index]) {
      current = null;
      return;
    }
    if (!current) {
      current = { lines: [] };
      hunks.push(current);
    }
    current.lines.push(op);
  });
  return hunks;
}
