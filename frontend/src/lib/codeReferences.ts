import type { CodeProjectScope, CodeReference } from "./api";
import type { FileRef } from "./presenters/types";

export function toCodeReference(file: FileRef, scopes: CodeProjectScope[]): CodeReference | null {
  if (
    !file.path ||
    !file.path.startsWith("/") ||
    file.path.includes("\0") ||
    hasParentSegment(file.path)
  ) {
    return null;
  }

  const match = scopes
    .map(normalizedScope)
    .filter((scope): scope is CodeProjectScope => scope !== null)
    .filter((scope) => file.path === scope.root || file.path.startsWith(`${scope.root}/`))
    .sort((left, right) => right.root.length - left.root.length)[0];
  if (!match) return null;

  const relativePath = file.path.slice(match.root.length + 1);
  if (!relativePath || relativePath.startsWith("/") || hasParentSegment(relativePath)) return null;

  return {
    projectKey: match.projectKey,
    relativePath,
    ...(Number.isInteger(file.line) && Number(file.line) >= 1 ? { line: Number(file.line) } : {}),
  };
}

function normalizedScope(scope: CodeProjectScope): CodeProjectScope | null {
  if (!scope.projectKey?.trim() || !scope.root?.startsWith("/") || scope.root.includes("\0"))
    return null;
  const root = scope.root.length > 1 ? scope.root.replace(/\/+$/u, "") : scope.root;
  if (isBroadRoot(root) || hasParentSegment(root)) return null;
  return { projectKey: scope.projectKey, root };
}

function hasParentSegment(path: string): boolean {
  return path.split("/").some((segment) => segment === "..");
}

function isBroadRoot(root: string): boolean {
  return (
    root === "/" ||
    /^\/Users\/[^/]+$/u.test(root) ||
    /^\/home\/[^/]+$/u.test(root) ||
    root === "__no_project__"
  );
}
