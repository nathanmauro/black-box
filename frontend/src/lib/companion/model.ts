import type { EventFeedItem, ProjectSummary } from "../api";
import { findProjectByIdentifier, projectShortName } from "../projects";
import { eventHref } from "./links";

export type PulseState = "connecting" | "live" | "idle" | "disconnected";
export type ConnectionState = "connecting" | "live" | "down";
export type MeaningfulKind = "decision" | "handoff" | "observation";
export type CompanionMode = "mini" | "compact" | "expanded";
// projectName travels with the view (set once, when it is opened) instead of being looked up from
// the live model on every render, so a project whose card ages out of the model while its view is
// still open (no live session, last item past the 24h window) keeps a real title and strip instead
// of falling back to the generic "Project".
export type ExpandedViewState =
  { kind: "project"; projectKey: string; projectName: string } | { kind: "river" };

export const MEANINGFUL_EVENT_TYPES: Readonly<Record<string, MeaningfulKind>> = {
  Decision: "decision",
  Handoff: "handoff",
  Observation: "observation",
};
// Object.hasOwn (not `in` or a bare index lookup) so inherited property names like "constructor" or
// "toString" never read as a meaningful kind.
export function meaningfulKindOf(eventType: string): MeaningfulKind | undefined {
  return Object.hasOwn(MEANINGFUL_EVENT_TYPES, eventType)
    ? MEANINGFUL_EVENT_TYPES[eventType]
    : undefined;
}
export function isMeaningfulEventType(eventType: string): boolean {
  return Object.hasOwn(MEANINGFUL_EVENT_TYPES, eventType);
}
// Grammar v2: a facet takes a comma IN-list; "OR" would be free text that must match.
export const MEANINGFUL_QUERY = "kind:decision,handoff,observation last:24h";
export const UNASSIGNED_KEY = "__unassigned__";
export const UNASSIGNED_NAME = "Unassigned";
export const LIVE_WINDOW_MS = 120_000;
export const ACTIVE_SESSION_WINDOW_MS = 10 * 60_000;
export const MEANINGFUL_WINDOW_MS = 24 * 60 * 60_000;
export const HEADLINE_MAX = 120;

export type MeaningfulItem = {
  id: string;
  kind: MeaningfulKind;
  eventType: string;
  projectKey: string;
  projectName: string;
  sessionId: string;
  source: string;
  headline: string;
  nextAction: string | null;
  openLoops: string[];
  observedAt: string;
  href: string;
  seen: boolean;
};

export type ProjectCard = {
  key: string;
  name: string;
  liveSessions: number;
  lastActivityAt: string | null;
  lastCaptureAt: string | null;
  unseen: number;
  latest: MeaningfulItem | null;
  items: MeaningfulItem[];
};

export type CompanionModel = {
  pulse: PulseState;
  lastEventAt: string | null;
  unseenTotal: number;
  projects: ProjectCard[];
  river: MeaningfulItem[];
};

export type SessionLiveness = { id: string; cwd: string | null; lastSeenAt: string };

export type DeriveInput = {
  now: number;
  connection: ConnectionState;
  lastEventAt: string | null;
  projects: ProjectSummary[];
  sessions: SessionLiveness[];
  events: EventFeedItem[];
  seen: ReadonlySet<string>;
};

type Metadata = {
  decision?: unknown;
  contextSummary?: unknown;
  nextAction?: unknown;
  openLoops?: unknown;
};

function metadataOf(event: EventFeedItem): Metadata {
  return event.metadata && typeof event.metadata === "object" ? (event.metadata as Metadata) : {};
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function timestamp(iso: string | null | undefined): number {
  const value = iso ? Date.parse(iso) : Number.NaN;
  return Number.isNaN(value) ? 0 : value;
}

function later(a: string | null, b: string | null | undefined): string | null {
  if (!b) return a;
  return timestamp(b) > timestamp(a) ? b : a;
}

export function headlineOf(event: EventFeedItem): string {
  const meta = metadataOf(event);
  const raw =
    asString(meta.decision) ??
    asString(meta.contextSummary) ??
    asString(event.text) ??
    event.eventType;
  const line =
    raw
      .split("\n")
      .map((part) => part.trim())
      .find(Boolean) ?? event.eventType;
  return line.length > HEADLINE_MAX ? `${line.slice(0, HEADLINE_MAX - 1).trimEnd()}…` : line;
}

function projectFor(
  projects: ProjectSummary[],
  cwd: string | null | undefined,
): { key: string; name: string } {
  const project = findProjectByIdentifier(projects, cwd);
  return project
    ? { key: project.projectKey, name: projectShortName(project) }
    : { key: UNASSIGNED_KEY, name: UNASSIGNED_NAME };
}

export function toMeaningfulItem(
  event: EventFeedItem,
  projects: ProjectSummary[],
  seen: ReadonlySet<string>,
): MeaningfulItem | null {
  const kind = meaningfulKindOf(event.eventType);
  if (!kind) return null;
  const meta = metadataOf(event);
  const project = projectFor(projects, event.cwd);
  const openLoops = Array.isArray(meta.openLoops)
    ? meta.openLoops.filter((loop): loop is string => typeof loop === "string")
    : [];
  return {
    id: event.id,
    kind,
    eventType: event.eventType,
    projectKey: project.key,
    projectName: project.name,
    sessionId: event.sessionId,
    source: event.source,
    headline: headlineOf(event),
    nextAction: asString(meta.nextAction),
    openLoops,
    observedAt: event.observedAt,
    href: eventHref(event.sessionId, event.id, project.key === UNASSIGNED_KEY ? null : project.key),
    seen: seen.has(event.id),
  };
}

export function pulseOf(
  connection: ConnectionState,
  lastEventAt: string | null,
  now: number,
): PulseState {
  if (connection === "down") return "disconnected";
  if (connection === "connecting") return "connecting";
  return lastEventAt && now - timestamp(lastEventAt) <= LIVE_WINDOW_MS ? "live" : "idle";
}

export type ItemsInput = Pick<DeriveInput, "now" | "projects" | "events" | "seen">;
export type ComposeInput = Omit<DeriveInput, "events" | "seen"> & { items: MeaningfulItem[] };

function sameItem(a: MeaningfulItem, b: MeaningfulItem): boolean {
  return (
    a.seen === b.seen &&
    a.projectKey === b.projectKey &&
    a.projectName === b.projectName &&
    a.observedAt === b.observedAt &&
    a.headline === b.headline &&
    a.nextAction === b.nextAction &&
    a.openLoops.length === b.openLoops.length
  );
}

/**
 * Meaningful items inside the window, deduplicated by id, newest first. Items whose content is
 * unchanged reuse their `previous` object, so keyed lists keep their rows across recomputes.
 */
export function deriveItems(
  input: ItemsInput,
  previous: readonly MeaningfulItem[] = [],
): MeaningfulItem[] {
  const prior = new Map(previous.map((item) => [item.id, item]));
  const byId = new Map<string, MeaningfulItem>();
  for (const event of input.events) {
    if (input.now - timestamp(event.observedAt) > MEANINGFUL_WINDOW_MS) continue;
    const item = toMeaningfulItem(event, input.projects, input.seen);
    if (!item) continue;
    const kept = prior.get(item.id);
    byId.set(item.id, kept && sameItem(kept, item) ? kept : item);
  }
  return [...byId.values()].sort((a, b) => timestamp(b.observedAt) - timestamp(a.observedAt));
}

export function deriveModel(input: DeriveInput): CompanionModel {
  return composeModel({ ...input, items: deriveItems(input) });
}

/** Cards and pulse over already-derived items; cheap enough to rerun on every activity frame. */
export function composeModel(input: ComposeInput): CompanionModel {
  const items = input.items;
  const cards = new Map<string, ProjectCard>();
  const ensure = (key: string, name: string): ProjectCard => {
    let card = cards.get(key);
    if (!card) {
      card = {
        key,
        name,
        liveSessions: 0,
        lastActivityAt: null,
        lastCaptureAt: null,
        unseen: 0,
        latest: null,
        items: [],
      };
      cards.set(key, card);
    }
    return card;
  };

  for (const session of input.sessions) {
    if (input.now - timestamp(session.lastSeenAt) > ACTIVE_SESSION_WINDOW_MS) continue;
    const project = projectFor(input.projects, session.cwd);
    const card = ensure(project.key, project.name);
    card.liveSessions += 1;
    card.lastActivityAt = later(card.lastActivityAt, session.lastSeenAt);
  }

  for (const item of items) {
    const card = ensure(item.projectKey, item.projectName);
    card.items.push(item);
    if (!item.seen) card.unseen += 1;
    if (!card.latest) card.latest = item;
    card.lastCaptureAt = later(card.lastCaptureAt, item.observedAt);
    card.lastActivityAt = later(card.lastActivityAt, item.observedAt);
  }

  const projects = [...cards.values()].sort(
    (a, b) =>
      b.unseen - a.unseen ||
      timestamp(b.lastActivityAt) - timestamp(a.lastActivityAt) ||
      a.name.localeCompare(b.name),
  );

  return {
    pulse: pulseOf(input.connection, input.lastEventAt, input.now),
    lastEventAt: input.lastEventAt,
    unseenTotal: items.filter((item) => !item.seen).length,
    projects,
    river: items,
  };
}
