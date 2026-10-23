import type { ProjectSummary } from "./api";

const LAYOUT_KEY = "bb.streamLayout";
const SPAN_KEY = "bb.windowSpan";

const HOUR_MS = 60 * 60_000;

export const WINDOW_SPANS = [
  { id: "24h", label: "24h", ms: 24 * HOUR_MS },
  { id: "48h", label: "48h", ms: 48 * HOUR_MS },
  { id: "7d", label: "7d", ms: 7 * 24 * HOUR_MS },
] as const;

export type WindowSpan = (typeof WINDOW_SPANS)[number]["id"];

export const DEFAULT_WINDOW_SPAN: WindowSpan = "48h";
export const ACTIVE_WINDOW_MS = 48 * HOUR_MS;
// Same "live" threshold as the companion: a session touched in the last 10 minutes.
export const LIVE_WINDOW_MS = 10 * 60_000;

export type StreamLayout = "stream" | "cards";

export function parseStreamLayout(value: string | null | undefined): StreamLayout | null {
  if (value === "cards" || value === "stream") {
    return value;
  }

  return null;
}

export function loadStreamLayout(): StreamLayout {
  try {
    return parseStreamLayout(localStorage.getItem(LAYOUT_KEY)) ?? "stream";
  } catch {
    return "stream";
  }
}

export function saveStreamLayout(layout: StreamLayout): void {
  try {
    localStorage.setItem(LAYOUT_KEY, layout);
  } catch {
    // Private-mode storage failures degrade to a session-only layout.
  }
}

export function windowSpanMs(span: WindowSpan): number {
  return WINDOW_SPANS.find((option) => option.id === span)?.ms ?? ACTIVE_WINDOW_MS;
}

export function loadWindowSpan(): WindowSpan {
  try {
    const stored = localStorage.getItem(SPAN_KEY);
    return WINDOW_SPANS.find((option) => option.id === stored)?.id ?? DEFAULT_WINDOW_SPAN;
  } catch {
    return DEFAULT_WINDOW_SPAN;
  }
}

export function saveWindowSpan(span: WindowSpan): void {
  try {
    localStorage.setItem(SPAN_KEY, span);
  } catch {
    // Private-mode storage failures degrade to a session-only span.
  }
}

export function isActiveSince(project: ProjectSummary, now: number, windowMs: number): boolean {
  const seen = project.lastSeenAt ? Date.parse(project.lastSeenAt) : Number.NaN;

  return !Number.isNaN(seen) && now - seen <= windowMs;
}

/**
 * Window order is learned by position, so it never reshuffles: keys already placed keep their
 * slot, and newly active projects append in most-recent-first order.
 */
export function stableWindowOrder(
  placed: string[],
  projects: ProjectSummary[],
  now: number,
  windowMs = ACTIVE_WINDOW_MS,
): string[] {
  const known = new Set(placed);
  const arrivals = projects
    .filter((project) => !known.has(project.projectKey))
    .filter((project) => isActiveSince(project, now, windowMs))
    .sort((left, right) => seenValue(right) - seenValue(left))
    .map((project) => project.projectKey);

  return arrivals.length ? [...placed, ...arrivals] : placed;
}

function seenValue(project: ProjectSummary): number {
  const seen = project.lastSeenAt ? Date.parse(project.lastSeenAt) : Number.NaN;

  return Number.isNaN(seen) ? 0 : seen;
}
