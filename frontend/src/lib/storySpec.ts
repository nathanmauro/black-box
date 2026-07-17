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

const DEFAULT_STORY_INPUT: StoryFormInput = {
  title: "",
  repo: "",
  mode: "full_auto",
  goal: "",
  acceptanceCriteria: "",
  constraints: "",
  verify: "",
  priority: 10,
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

export function parseSpecBody(body: string): StoryFormInput {
  const lines = body.replace(/\r\n?/g, "\n").split("\n");
  const { frontmatter, markdown } = splitSpecDocument(lines);
  const fields = parseFrontmatter(frontmatter);
  const sections = parseOwnedSections(markdown);
  const priorityValue = parseYamlScalar(fields.priority ?? "");
  const parsedPriority = priorityValue ? Number(priorityValue) : Number.NaN;

  return {
    ...DEFAULT_STORY_INPUT,
    title: parseTitle(markdown),
    repo: parseYamlScalar(fields.repo ?? ""),
    mode: parseYamlScalar(fields.mode ?? "") === "sdlc" ? "sdlc" : "full_auto",
    goal: trimBlankEdges(sections.goal).join("\n"),
    acceptanceCriteria: parseListSection(sections.acceptanceCriteria),
    constraints: parseConstraints(sections.constraints),
    verify: parseYamlScalar(fields.verify ?? ""),
    priority: Number.isFinite(parsedPriority) ? parsedPriority : DEFAULT_STORY_INPUT.priority,
  };
}

function splitSpecDocument(lines: string[]): { frontmatter: string[]; markdown: string[] } {
  if (lines[0]?.trim() !== "---") return { frontmatter: [], markdown: lines };
  const closingIndex = lines.findIndex((line, index) => index > 0 && line.trim() === "---");
  if (closingIndex === -1) return { frontmatter: [], markdown: lines };
  return {
    frontmatter: lines.slice(1, closingIndex),
    markdown: lines.slice(closingIndex + 1),
  };
}

function parseFrontmatter(lines: string[]): Record<string, string> {
  const fields: Record<string, string> = {};
  for (const line of lines) {
    const match = /^([A-Za-z0-9_-]+)\s*:\s*(.*)$/.exec(line);
    if (match) fields[match[1]!.toLowerCase()] = match[2] ?? "";
  }
  return fields;
}

function parseYamlScalar(value: string): string {
  const trimmed = value.trim();
  if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
    try {
      const parsed = JSON.parse(trimmed);
      if (typeof parsed === "string") return parsed;
    } catch {
      return trimmed.slice(1, -1).replace(/\\(["\\])/g, "$1");
    }
  }
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
    return trimmed.slice(1, -1).replace(/''/g, "'");
  }
  return trimmed;
}

function parseTitle(lines: string[]): string {
  const heading = lines.find((line) => /^#\s+\S/.test(line.trim()));
  return heading?.trim().replace(/^#\s+/, "").trim() ?? "";
}

type OwnedSections = {
  goal: string[];
  acceptanceCriteria: string[];
  constraints: string[];
};

function parseOwnedSections(lines: string[]): OwnedSections {
  const acceptanceIndex = findLastSectionHeading(lines, "acceptanceCriteria");
  const constraintsIndex = findLastSectionHeading(lines, "constraints", acceptanceIndex + 1);
  const goalBoundary = acceptanceIndex >= 0
    ? acceptanceIndex
    : constraintsIndex >= 0 ? constraintsIndex : lines.length;
  const goalIndex = lines.findIndex((line, index) => (
    index < goalBoundary && ownedSectionHeading(line) === "goal"
  ));

  return {
    goal: goalIndex >= 0 ? lines.slice(goalIndex + 1, goalBoundary) : [],
    acceptanceCriteria: acceptanceIndex >= 0
      ? lines.slice(acceptanceIndex + 1, constraintsIndex >= 0 ? constraintsIndex : lines.length)
      : [],
    constraints: constraintsIndex >= 0 ? lines.slice(constraintsIndex + 1) : [],
  };
}

function findLastSectionHeading(
  lines: string[],
  heading: keyof OwnedSections,
  startIndex = 0,
): number {
  for (let index = lines.length - 1; index >= startIndex; index -= 1) {
    if (ownedSectionHeading(lines[index]!) === heading) return index;
  }
  return -1;
}

function ownedSectionHeading(line: string): keyof OwnedSections | undefined {
  const normalized = line.trim().toLowerCase();
  if (normalized === "## goal") return "goal";
  if (normalized === "## acceptance criteria") return "acceptanceCriteria";
  if (normalized === "## constraints / dangers") return "constraints";
  return undefined;
}

function parseListSection(lines: string[]): string {
  return trimBlankEdges(lines)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.startsWith("- ") ? line.slice(2) : line)
    .join("\n");
}

function parseConstraints(lines: string[]): string {
  const constraints = parseListSection(lines);
  return constraints === "None specified." ? "" : constraints;
}

function trimBlankEdges(lines: string[]): string[] {
  let start = 0;
  let end = lines.length;
  while (start < end && !lines[start]!.trim()) start += 1;
  while (end > start && !lines[end - 1]!.trim()) end -= 1;
  return lines.slice(start, end);
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
