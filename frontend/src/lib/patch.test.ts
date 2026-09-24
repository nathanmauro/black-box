import { describe, expect, it } from "vitest";
import { parseApplyPatch, patchFileStubs } from "./patch";

const UPDATE_PATCH = [
  "*** Begin Patch",
  "*** Update File: /Users/nathan/Developer/proj/sba-agentic/README.md",
  "@@",
  " intro line",
  "-old line",
  "+new line",
  "*** End Patch",
].join("\n");

const MULTI_PATCH = [
  "*** Begin Patch",
  "*** Add File: /tmp/a.txt",
  "+hello",
  "*** Delete File: /tmp/b.txt",
  "*** End Patch",
].join("\n");

describe("patchFileStubs", () => {
  it("lists file operations without parsing hunks", () => {
    expect(patchFileStubs(MULTI_PATCH)).toEqual([
      { op: "add", path: "/tmp/a.txt", movedTo: null },
      { op: "delete", path: "/tmp/b.txt", movedTo: null },
    ]);
  });
});

describe("parseApplyPatch", () => {
  it("parses an update patch into hunks with add/del/context kinds", () => {
    const files = parseApplyPatch(UPDATE_PATCH);
    expect(files).toHaveLength(1);
    expect(files![0]).toMatchObject({
      op: "update",
      path: "/Users/nathan/Developer/proj/sba-agentic/README.md",
    });
    const kinds = files![0].hunks[0].lines.map((line) => `${line.kind}:${line.text}`);
    expect(kinds).toEqual(["context:intro line", "del:old line", "add:new line"]);
  });

  it("parses add-file bodies as additions", () => {
    const files = parseApplyPatch(MULTI_PATCH);
    expect(files![0].hunks[0].lines).toEqual([
      { kind: "add", text: "hello", oldLine: null, newLine: null },
    ]);
    expect(files![1].hunks).toEqual([]);
  });

  it("captures Move to targets", () => {
    const moved = parseApplyPatch(
      "*** Begin Patch\n*** Update File: /tmp/old.txt\n*** Move to: /tmp/new.txt\n@@\n+x\n*** End Patch",
    );
    expect(moved![0].movedTo).toBe("/tmp/new.txt");
  });

  it("returns null for content that is not a patch", () => {
    expect(parseApplyPatch("just some text")).toBeNull();
    expect(
      parseApplyPatch("*** Begin Patch\ngarbage before any file header\n*** End Patch"),
    ).toBeNull();
  });
});
