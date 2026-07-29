import { describe, expect, it } from "vitest";
import type { CodeProjectScope } from "./api";
import { toCodeReference } from "./codeReferences";

describe("toCodeReference", () => {
  const scopes: CodeProjectScope[] = [
    { projectKey: "repo", root: "/Users/nathan/Developer/proj/example" },
    { projectKey: "worktree", root: "/Users/nathan/Developer/proj/example/.worktrees/slice" },
  ];

  it("uses a boundary-aware longest-prefix match and preserves the exact worktree key", () => {
    expect(toCodeReference(
      { path: "/Users/nathan/Developer/proj/example/.worktrees/slice/src/App.ts", line: 7 },
      scopes,
    )).toEqual({
      projectKey: "worktree",
      relativePath: "src/App.ts",
      line: 7,
    });

    expect(toCodeReference(
      { path: "/Users/nathan/Developer/proj/example-other/src/App.ts" },
      scopes,
    )).toBeNull();
  });

  it("keeps spaces and unicode while omitting invalid positions", () => {
    expect(toCodeReference(
      { path: "/Users/nathan/Developer/proj/example/src/naïve file.ts", line: 0 },
      scopes,
    )).toEqual({
      projectKey: "repo",
      relativePath: "src/naïve file.ts",
    });
  });

  it("fails closed for broad roots, relative paths, traversal, and unmatched paths", () => {
    expect(toCodeReference({ path: "/etc/passwd" }, [{ projectKey: "root", root: "/" }])).toBeNull();
    expect(toCodeReference(
      { path: "/Users/nathan/.ssh/config" },
      [{ projectKey: "home", root: "/Users/nathan" }],
    )).toBeNull();
    expect(toCodeReference({ path: "src/App.ts" }, scopes)).toBeNull();
    expect(toCodeReference(
      { path: "/Users/nathan/Developer/proj/example/src/../secret" },
      scopes,
    )).toBeNull();
    expect(toCodeReference({ path: "/tmp/outside.ts" }, scopes)).toBeNull();
  });
});
