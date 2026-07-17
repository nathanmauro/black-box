import { describe, expect, it } from "vitest";
import { assembleSpecBody, evaluateGateHints, parseSpecBody, type StoryFormInput } from "./storySpec";

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

describe("parseSpecBody", () => {
  it("round-trips every form-owned field through the canonical builder", () => {
    const body = assembleSpecBody({
      ...validInput,
      title: "  Build the board runner  ",
      repo: '/tmp\\runner"checkout',
      mode: "sdlc",
      goal: `  Run approved stories.

## Goal
Keep this nested heading in the goal.

## Acceptance criteria
This heading is also goal context.

## Constraints / dangers
Keep the frozen contract.  `,
      acceptanceCriteria: "  Gate validates the story.\n\n- Runner claims the task.  ",
      constraints: "  Preserve local state.\nDo not force push.  ",
      verify: '  npm test -- --name="runner"  ',
      priority: 23,
    });

    expect(parseSpecBody(body)).toEqual({
      title: "Build the board runner",
      repo: '/tmp\\runner"checkout',
      mode: "sdlc",
      goal: `Run approved stories.

## Goal
Keep this nested heading in the goal.

## Acceptance criteria
This heading is also goal context.

## Constraints / dangers
Keep the frozen contract.`,
      acceptanceCriteria: "Gate validates the story.\n- Runner claims the task.",
      constraints: "Preserve local state.\nDo not force push.",
      verify: 'npm test -- --name="runner"',
      priority: 23,
    });
    expect(assembleSpecBody(parseSpecBody(body))).toBe(body);
  });

  it("restores omitted verify and the canonical empty-constraints sentinel", () => {
    const parsed = parseSpecBody(assembleSpecBody({
      ...validInput,
      verify: "  ",
      constraints: "\n ",
    }));

    expect(parsed.verify).toBe("");
    expect(parsed.constraints).toBe("");
  });

  it.each([
    ["Goal", "goal"],
    ["Acceptance criteria", "acceptanceCriteria"],
    ["Constraints / dangers", "constraints"],
  ] as const)("defaults a missing %s section to an empty field", (heading, field) => {
    const body = assembleSpecBody(validInput).replace(
      new RegExp(`\\n## ${heading}\\n[\\s\\S]*?(?=\\n## |$)`),
      "",
    );

    expect(parseSpecBody(body)[field]).toBe("");
  });

  it("ignores extra frontmatter keys and defaults absent owned values", () => {
    expect(parseSpecBody(`---
story: v1
repo: '/tmp/black-box'
mode: "sdlc"
owner: delivery
push: false
priority: "23"
custom: { retained: outside-the-form }
---

# Resubmit the blocked story

## Goal

Address the gate feedback.
`)).toEqual({
      title: "Resubmit the blocked story",
      repo: "/tmp/black-box",
      mode: "sdlc",
      goal: "Address the gate feedback.",
      acceptanceCriteria: "",
      constraints: "",
      verify: "",
      priority: 23,
    });
  });

  it("defaults blank and invalid priority values", () => {
    const document = (priority: string) => `---
priority: ${priority}
---

# Story
`;

    expect(parseSpecBody(document("")).priority).toBe(10);
    expect(parseSpecBody(document("nope")).priority).toBe(10);
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

  it("accepts SDLC mode without an unsupported-mode hint", () => {
    expect(evaluateGateHints({ ...validInput, mode: "sdlc" }).map((hint) => hint.id))
      .not.toContain("mode-unsupported");
  });

  it.each([0, 101, Number.NaN])("reports an invalid priority for %s", (priority) => {
    expect(evaluateGateHints({ ...validInput, priority }).map((hint) => hint.id))
      .toContain("priority-out-of-range");
  });
});
