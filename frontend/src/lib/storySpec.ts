export type StoryFormInput = {
  title: string;
  repo: string;
  mode: "full_auto" | "sdlc";
  goal: string;
  acceptanceCriteria: string;
  constraints: string;
  verify: string;
  priority: number;
};

export type GateHint = {
  id: string;
  message: string;
};

export function assembleSpecBody(input: StoryFormInput): string {
  const criteria = nonBlankLines(input.acceptanceCriteria).map((line) => `- ${line}`);
  const constraints = nonBlankLines(input.constraints).map((line) => `- ${line}`);
  const verify = input.verify.trim();
  const lines = [
    "---",
    "story: v1",
    `repo: ${yamlDoubleQuoted(input.repo.trim())}`,
    `mode: ${input.mode}`,
    ...(verify ? [`verify: ${yamlDoubleQuoted(verify)}`] : []),
    "push: true",
    `priority: ${input.priority}`,
    "---",
    "",
    `# ${input.title.trim()}`,
    "",
    "## Goal",
    "",
    input.goal.trim(),
    "",
    "## Acceptance criteria",
    "",
    ...criteria,
    "",
    "## Constraints / dangers",
    "",
    ...(constraints.length ? constraints : ["- None specified."]),
    "",
  ];

  return lines.join("\n");
}

function yamlDoubleQuoted(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

export function evaluateGateHints(input: StoryFormInput): GateHint[] {
  const hints: GateHint[] = [];
  const repo = input.repo.trim();

  if (!repo) {
    hints.push({
      id: "repo-required",
      message: "Repo path is required for the gate's allowlist check.",
    });
  } else if (!repo.startsWith("/")) {
    hints.push({
      id: "repo-not-absolute",
      message: "Repo should be an absolute path (e.g. /Users/name/Developer/proj/name).",
    });
  }

  if (nonBlankLines(input.acceptanceCriteria).length === 0) {
    hints.push({
      id: "acceptance-criteria-empty",
      message: "Acceptance criteria is empty — the gate will block the story until at least one criterion is listed.",
    });
  }

  if (!input.verify.trim()) {
    hints.push({
      id: "verify-missing",
      message: "No verify command set — the gate will try to derive one from repo convention (mvn/npm/make); set one explicitly if that's wrong for this repo.",
    });
  }

  if (input.mode === "sdlc") {
    hints.push({
      id: "mode-unsupported",
      message: "SDLC mode is not runnable yet — this story will sit in the gate lane until full_auto mode is selected.",
    });
  }

  if (!Number.isFinite(input.priority) || input.priority < 1 || input.priority > 100) {
    hints.push({
      id: "priority-out-of-range",
      message: "Priority should be a number between 1 and 100.",
    });
  }

  return hints;
}

function nonBlankLines(value: string): string[] {
  return value
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}
