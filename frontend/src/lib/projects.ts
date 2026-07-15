import type { AgentSession, ProjectScope, ProjectSummary } from "./api";

const REMEMBERED_PROJECT_KEY = "blackbox.activity.projectKey";
export const NO_PROJECT_SCOPE = "__no_project__";

export function projectShortName(project: ProjectSummary): string {
  const value = primaryProjectScope(project).canonicalKey || project.label || project.projectKey;
  if (canonicalizeProjectPath(value) === NO_PROJECT_SCOPE) {
    return friendlyNoProjectLabel(project.label);
  }
  const parts = value.split("/").filter(Boolean);
  return parts[parts.length - 1] || value;
}

export function projectSearchText(project: ProjectSummary): string {
  const scopeText = projectScopes(project).flatMap((scope) => [scope.label, scope.canonicalKey, scope.projectKey]);
  return [projectShortName(project), project.label, project.canonicalKey, project.projectKey, ...scopeText]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
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
  const cwd = canonicalizeProjectPath(session.cwd);
  return projectScopes(project).some((scope) => canonicalizeProjectPath(scope.canonicalKey) === cwd);
}

export function projectScopes(project: ProjectSummary): ProjectScope[] {
  const scopes = project.scopes?.length
    ? project.scopes
    : [{ projectKey: project.projectKey, canonicalKey: project.canonicalKey, label: project.label, primary: true }];
  return [...scopes].sort((left, right) => Number(right.primary) - Number(left.primary));
}

export function primaryProjectScope(project: ProjectSummary): ProjectScope {
  return (
    projectScopes(project).find((scope) => scope.primary) ?? {
      projectKey: project.projectKey,
      canonicalKey: project.canonicalKey,
      label: project.label,
      primary: true,
    }
  );
}

export function findProjectByIdentifier(
  projects: ProjectSummary[],
  identifier: string | null | undefined,
): ProjectSummary | undefined {
  if (!identifier) return undefined;
  const canonicalIdentifier = canonicalizeProjectPath(identifier);
  return projects.find((project) =>
    projectScopes(project).some(
      (scope) =>
        scope.projectKey === identifier ||
        scope.canonicalKey === identifier ||
        canonicalizeProjectPath(scope.canonicalKey) === canonicalIdentifier,
    ),
  );
}

export function projectScopeDisplayName(scope: Pick<ProjectScope, "canonicalKey" | "label">): string {
  if (canonicalizeProjectPath(scope.canonicalKey) === NO_PROJECT_SCOPE) return friendlyNoProjectLabel(scope.label);
  return scope.label || scope.canonicalKey;
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
  const exactScope = projectScopes(project).some((scope) =>
    [scope.projectKey, scope.canonicalKey, scope.label].some((value) => value?.toLowerCase() === needle),
  );
  if (exactScope) return 50;
  return projectSearchText(project).includes(needle) ? 30 : 0;
}

function friendlyNoProjectLabel(label: string | null | undefined): string {
  const normalized = label?.trim();
  return normalized && normalized !== NO_PROJECT_SCOPE ? normalized : "No project / system context";
}

function timestampValue(value: string | null | undefined): number {
  return value ? Date.parse(value) || 0 : 0;
}
