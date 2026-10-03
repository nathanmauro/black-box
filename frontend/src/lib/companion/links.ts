// Per-event position link into the Black Box browse view. Mirrors StreamPage's private
// sessionHref so the companion never depends on StreamPage internals.
export function eventHref(sessionId: string, eventId: string, projectKey: string | null): string {
  const query = new URLSearchParams({ view: "browse", session: sessionId, event: eventId });
  if (projectKey) query.set("project", projectKey);
  return `/?${query.toString()}`;
}

export const RECALL_MIN_QUERY = 2;

// Hands a question to the existing Recall page, which runs one recall for run=1. A null project
// means all projects (the river, or rows outside the catalog).
export function recallHref(query: string, project: string | null): string {
  const params = new URLSearchParams();
  if (project) params.set("project", project);
  params.set("query", query.trim());
  params.set("run", "1");
  return `/recall?${params.toString()}`;
}
