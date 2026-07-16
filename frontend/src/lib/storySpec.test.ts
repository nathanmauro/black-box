import { describe, expect, it } from "vitest";
import { assembleSpecBody, evaluateGateHints, type StoryFormInput } from "./storySpec";

const validInput: StoryFormInput = {
  title: "Build the board runner",
  repo: "/Users/nathan/Developer/proj/sba-agentic",
  mode: "full_auto",
  goal: "Run approved stories from the board.",
  acceptanceCriteria: "The gate validates the story.\nThe runner claims the task.",
  constraints: "Preserve local state.\nDo not force push.",
  verify: "mvn test",
  priority: 10,
};

describe("assembleSpecBody", () => {
  it("assembles the exact story document for a fully-filled input", () => {
    expect(assembleSpecBody({
      ...validInput,
      title: "  Build the board runner  ",
      repo: "  /Users/nathan/Developer/proj/sba-agentic  ",
      goal: "  Run approved stories from the board.  ",
      verify: "  mvn test  ",
    })).toBe(`---
story: v1
repo: "/Users/nathan/Developer/proj/sba-agentic"
mode: full_auto
verify: "mvn test"
push: true
priority: 10
---

# Build the board runner

## Goal

Run approved stories from the board.

## Acceptance criteria

- The gate validates the story.
- The runner claims the task.

## Constraints / dangers

- Preserve local state.
- Do not force push.
`);
  });

  it("omits the verify field when the command is blank", () => {
    const body = assembleSpecBody({ ...validInput, verify: "  \n " });

    expect(body).not.toContain("verify:");
    expect(body).toContain("mode: full_auto\npush: true");
  });

  it("uses an explicit fallback when constraints are blank", () => {
    const body = assembleSpecBody({ ...validInput, constraints: " \n " });

    expect(body).toContain("## Constraints / dangers\n\n- None specified.\n");
  });

  it("turns non-blank trimmed textarea lines into bullets", () => {
    const body = assembleSpecBody({
      ...validInput,
      acceptanceCriteria: "  First criterion  \n\n  Second criterion\n ",
      constraints: " First constraint\n \nSecond constraint  ",
    });

    expect(body).toContain("## Acceptance criteria\n\n- First criterion\n- Second criterion\n");
    expect(body).toContain("## Constraints / dangers\n\n- First constraint\n- Second constraint\n");
  });

  it("escapes double quotes and backslashes in YAML scalar values", () => {
    const body = assembleSpecBody({
      ...validInput,
      repo: '/tmp\\repo"name',
      verify: 'mvn test -Dfoo="bar"',
    });

    expect(body.split("\n")).toEqual(expect.arrayContaining([
      'repo: "/tmp\\\\repo\\"name"',
      'verify: "mvn test -Dfoo=\\"bar\\""',
    ]));
  });
});

describe("evaluateGateHints", () => {
  it("returns no hints for a valid story", () => {
    expect(evaluateGateHints(validInput)).toEqual([]);
  });

  it("reports a blank repo without also reporting a relative path", () => {
    const hints = evaluateGateHints({ ...validInput, repo: "  " });

    expect(hints.map((hint) => hint.id)).toContain("repo-required");
    expect(hints.map((hint) => hint.id)).not.toContain("repo-not-absolute");
  });

  it("reports a relative repo path", () => {
    expect(evaluateGateHints({ ...validInput, repo: "repos/black-box" }).map((hint) => hint.id))
      .toContain("repo-not-absolute");
  });

  it.each(["", " \n \n "])("reports empty acceptance criteria for %j", (acceptanceCriteria) => {
    expect(evaluateGateHints({ ...validInput, acceptanceCriteria }).map((hint) => hint.id))
      .toContain("acceptance-criteria-empty");
  });

  it("reports a missing verify command", () => {
    expect(evaluateGateHints({ ...validInput, verify: " \n " }).map((hint) => hint.id))
      .toContain("verify-missing");
  });

  it("reports the unsupported SDLC mode", () => {
    expect(evaluateGateHints({ ...validInput, mode: "sdlc" }).map((hint) => hint.id))
      .toContain("mode-unsupported");
  });

  it.each([0, 101, Number.NaN])("reports an invalid priority for %s", (priority) => {
    expect(evaluateGateHints({ ...validInput, priority }).map((hint) => hint.id))
      .toContain("priority-out-of-range");
  });
});
