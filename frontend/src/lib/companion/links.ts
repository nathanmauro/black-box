// Per-event position link into the Black Box browse view. Mirrors StreamPage's private
// sessionHref so the companion never depends on StreamPage internals.
export function eventHref(sessionId: string, eventId: string, projectKey: string | null): string {
  const query = new URLSearchParams({ view: "browse", session: sessionId, event: eventId });
  if (projectKey) query.set("project", projectKey);
  return `/?${query.toString()}`;
}
