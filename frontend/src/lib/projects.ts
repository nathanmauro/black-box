import type { AgentSession, ProjectSummary } from "./api";

const REMEMBERED_PROJECT_KEY = "blackbox.activity.projectKey";

export function projectShortName(project: ProjectSummary): string {
  const value = project.canonicalKey || project.label || project.projectKey;
  const parts = value.split("/").filter(Boolean);
  return parts[parts.length - 1] || value;
}

export function projectSearchText(project: ProjectSummary): string {
  return [projectShortName(project), project.label, project.canonicalKey, project.projectKey].filter(Boolean).join(" ").toLowerCase();
}

export function rankProjects(projects: ProjectSummary[], query: string): ProjectSummary[] {
  const needle = query.trim().toLowerCase();
  const ranked = [...projects].sort((left, right) => timestampValue(right.lastSeenAt) - timestampValue(left.lastSeenAt));
  if (!needle) return ranked;
  return ranked
    .map((project) => ({ project, score: scoreProject(project, needle) }))
    .filter((entry) => entry.score > 0)
    .sort((left, right) => right.score - left.score || timestampValue(right.project.lastSeenAt) - timestampValue(left.project.lastSeenAt))
    .map((entry) => entry.project);
}

export function projectMatchesSession(project: ProjectSummary, session: Pick<AgentSession, "cwd">): boolean {
  return canonicalizeProjectPath(session.cwd) === canonicalizeProjectPath(project.canonicalKey);
}

export function canonicalizeProjectPath(value: string | null | undefined): string {
  const trimmed = (value ?? "").trim();
  if (!trimmed) return "__no_project__";
  return trimmed.length > 1 ? trimmed.replace(/\/+$/u, "") : trimmed;
}

export function rememberProjectKey(projectKey: string | null | undefined): void {
  try {
    if (projectKey) localStorage.setItem(REMEMBERED_PROJECT_KEY, projectKey);
    else localStorage.removeItem(REMEMBERED_PROJECT_KEY);
  } catch {
    // Local storage is best-effort UI state.
  }
}

export function readRememberedProjectKey(): string | null {
  try {
    return localStorage.getItem(REMEMBERED_PROJECT_KEY);
  } catch {
    return null;
  }
}

export function clearRememberedProjectKey(): void {
  rememberProjectKey(null);
}

function scoreProject(project: ProjectSummary, needle: string): number {
  const short = projectShortName(project).toLowerCase();
  if (short === needle) return 100;
  if (short.startsWith(needle)) return 80;
  if (short.includes(needle)) return 60;
  return projectSearchText(project).includes(needle) ? 30 : 0;
}

function timestampValue(value: string | null | undefined): number {
  return value ? Date.parse(value) || 0 : 0;
}
