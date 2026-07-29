import { describe, expect, it } from "vitest";
import { allAdditions, diffLines, memoizedDiffLines } from "./diff";

const flat = (hunks: ReturnType<typeof diffLines>) =>
  hunks.flatMap((hunk) => hunk.lines).map((line) => `${line.kind[0]}:${line.text}`);

describe("diffLines", () => {
  it("returns no hunks for identical text", () => {
    expect(diffLines("a\nb", "a\nb")).toEqual([]);
  });

  it("diffs a one-line change with surrounding context", () => {
    const hunks = diffLines("const a = 1;\nconst b = 2;\nconst c = 3;", "const a = 1;\nconst b = 9;\nconst c = 3;");
    expect(flat(hunks)).toEqual(["c:const a = 1;", "d:const b = 2;", "a:const b = 9;", "c:const c = 3;"]);
  });

  it("numbers old and new lines correctly", () => {
    const [hunk] = diffLines("keep\nold", "keep\nnew");
    const del = hunk.lines.find((line) => line.kind === "del");
    const add = hunk.lines.find((line) => line.kind === "add");
    expect(del).toMatchObject({ oldLine: 2, newLine: null });
    expect(add).toMatchObject({ oldLine: null, newLine: 2 });
  });

  it("limits context and splits distant changes into separate hunks", () => {
    const oldText = ["x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10"].join("\n");
    const newText = ["CHANGED0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "CHANGED10"].join("\n");
    const hunks = diffLines(oldText, newText, 3);
    expect(hunks).toHaveLength(2);
  });

  it("degrades huge inputs to whole-block replace instead of hanging", () => {
    const oldText = Array.from({ length: 3000 }, (_, index) => `left ${index}`).join("\n");
    const newText = Array.from({ length: 3000 }, (_, index) => `right ${index}`).join("\n");
    const started = Date.now();
    const hunks = diffLines(oldText, newText);
    expect(Date.now() - started).toBeLessThan(2000);
    expect(hunks.length).toBeGreaterThan(0);
  });

  it("handles empty old text (pure addition)", () => {
    expect(flat(diffLines("", "a\nb"))).toEqual(["a:a", "a:b"]);
  });
});

describe("allAdditions", () => {
  it("renders every line as an add", () => {
    expect(flat(allAdditions("a\nb"))).toEqual(["a:a", "a:b"]);
  });
});

describe("memoizedDiffLines", () => {
  it("returns the identical array for a repeated key", () => {
    const first = memoizedDiffLines("evt:0", "a", "b");
    expect(memoizedDiffLines("evt:0", "a", "b")).toBe(first);
  });
});
