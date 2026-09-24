# Ambient Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the first slice of the Black Box ambient companion: a chrome-less `/companion` route (mini chip, compact project list, expanded project/river view with deep links) plus a thin macOS menubar-and-floating-panel shell that hosts it.

**Architecture:** One pure derived model (`lib/companion/model.ts`) computed in the existing SolidJS app from the existing SSE stream and `/api/events` query; Solid signals wire it to three small components; the backend only learns to forward `/companion` to `index.html`; a Swift Package executable hosts the route in a non-activating floating `NSPanel` and mirrors pulse plus unseen count into an `NSStatusItem` through a `WKScriptMessageHandler` bridge.

**Tech Stack:** SolidJS 1.9 + Vite 8 + TypeScript (frontend/), Vitest 3 + @solidjs/testing-library, Playwright 1.54 (isolated jar on port 8799), Java 21 + Spring Boot + JUnit/MockMvc, Swift 6.3 (Xcode 26.4.1) SwiftPM executable targeting macOS 13.

**Spec:** `docs/superpowers/specs/2026-09-24-ambient-companion-design.md`

## Global Constraints

- Work only inside the dedicated worktree and branch `companion-first-slice`. Sibling sessions are editing this repo in other worktrees; touch only the files listed in this plan.
- Never point anything at port 8766 or the production database for tests. Playwright runs the isolated jar on `http://127.0.0.1:8799`.
- Any `mvn package` (including `npm run e2e`) overwrites the jar the live service runs from. Immediately after: `launchctl kickstart -k gui/$UID/com.nathan.sba-agentic`.
- `npm run build` emits into `src/main/resources/static/`, which is committed; rebuild and commit it once at the end of the frontend work (Task 9).
- Meaningful event types are exactly `Decision`, `Handoff`, `Observation`. Task-board transitions are out of scope (the board is being retired in parallel).
- Backfill query string is exactly `kind:decision,handoff,observation last:24h` (corrected post-ship: the parser treats a facet as a comma IN-list, so the originally planned `OR` form here parses as a required free-text term and matches nothing; verified against the live API on 2026-09-24).
- Windows: live pulse 120 000 ms; live session 600 000 ms; headline max 120 chars; seen cap 2000 ids.
- Panel sizes: mini 132×36, compact 340×420, expanded 400×560.
- Commit style: human-readable Title Case subjects, Nathan as sole author, no AI co-author or generated-by trailers. Commit after every task. Do not push, do not open a PR; report the branch state in the final handoff.
- No CORS changes, no new REST endpoints, no writes to Black Box from the companion.

## Review Focus

1. An event whose `cwd` matches no project (or is null) must land in the single `Unassigned` card, never be dropped and never crash. Pinned in Task 1 (`groups unresolved cwd under Unassigned`).
2. A `Handoff` with text only and no metadata must still produce a headline from the first text line and no next action. Pinned in Task 1 (`falls back to the first text line`).
3. When the stream drops, the pulse must read `disconnected` while the last known counts stay visible, and a `down → live` transition must refetch. Pinned in Task 5 (`refetches after reconnect`).
4. `localStorage` that throws (private mode, blocked storage) must not break the page: the seen store and mode persistence fall back to memory. Pinned in Task 2 (`works without storage`) and Task 5 (`survives a throwing storage`).
5. A bridge `mode` message with an absurd or negative size must be clamped so the panel never disappears or exceeds the screen. Pinned in Task 10 (`testClampsSizeAndPosition`).

---

### Task 0: Reconcile Linear

**Files:** none in the repo.

- [ ] **Step 1: Search Linear** (MCP `list_issues`, query `companion`) for an existing issue about the ambient companion. If one exists, add a comment linking the spec and plan paths and stop.
- [ ] **Step 2: Create exactly one issue** if none exists: title `Ambient Companion First Slice`, in the Black Box project if `list_projects` shows one (otherwise the team default), description:

```
First build slice of the Black Box ambient companion (accepted concept, Black Box Decision 5ce7cce9-3dca-4fc1-b4a0-cf0b40529679).
Spec: docs/superpowers/specs/2026-09-24-ambient-companion-design.md
Plan: docs/superpowers/plans/2026-09-24-ambient-companion.md
Branch: companion-first-slice (worktree). Not pushed until Nathan says so.
```

- [ ] **Step 3: Note the issue identifier** for the final handoff.

---

### Task 1: Companion model (pure derivation)

**Files:**
- Create: `frontend/src/lib/companion/links.ts`
- Create: `frontend/src/lib/companion/model.ts`
- Test: `frontend/src/lib/companion/model.test.ts`

**Interfaces:**
- Consumes: `EventFeedItem`, `ProjectSummary` from `../api`; `findProjectByIdentifier`, `projectShortName` from `../projects`.
- Produces: `deriveModel(input: DeriveInput): CompanionModel`, `toMeaningfulItem`, `headlineOf`, `pulseOf`, constants `MEANINGFUL_EVENT_TYPES`, `MEANINGFUL_QUERY`, `UNASSIGNED_KEY`, `LIVE_WINDOW_MS`, `ACTIVE_SESSION_WINDOW_MS`, types `PulseState`, `ConnectionState`, `MeaningfulItem`, `ProjectCard`, `CompanionModel`, `SessionLiveness`, `DeriveInput`, `CompanionMode`, `ExpandedViewState`; `eventHref(sessionId, eventId, projectKey)`.

- [ ] **Step 1: Write the failing tests**

```ts
// frontend/src/lib/companion/model.test.ts
import { describe, expect, it } from "vitest";
import type { EventFeedItem, ProjectSummary } from "../api";
import { deriveModel, headlineOf, pulseOf, toMeaningfulItem, UNASSIGNED_KEY } from "./model";
import { eventHref } from "./links";

const NOW = Date.parse("2026-09-24T12:00:00Z");
const iso = (offsetMs: number) => new Date(NOW - offsetMs).toISOString();

function project(path: string, key = Buffer.from(path).toString("base64url")): ProjectSummary {
  return {
    projectKey: key,
    canonicalKey: path,
    label: path,
    sessionCount: 1,
    eventCount: 1,
    savedMeldCount: 0,
    firstSeenAt: iso(86_400_000),
    lastSeenAt: iso(0),
    scopes: [{ projectKey: key, canonicalKey: path, label: path, primary: true }],
  };
}

function event(overrides: Partial<EventFeedItem> & { id: string }): EventFeedItem {
  return {
    sessionId: "s1",
    source: "claude",
    clientSessionId: "c1",
    eventType: "Decision",
    text: "Use X\n\nWhy: because",
    cwd: "/repo/a",
    observedAt: iso(60_000),
    ...overrides,
  };
}

const projects = [project("/repo/a"), project("/repo/b")];

describe("headlineOf", () => {
  it("prefers metadata.decision, then contextSummary, then the first text line", () => {
    expect(headlineOf(event({ id: "1", metadata: { decision: "Pick SQLite" } }))).toBe("Pick SQLite");
    expect(headlineOf(event({ id: "2", eventType: "Handoff", metadata: { contextSummary: "Done: tests\nNext: ship" } }))).toBe("Done: tests");
    expect(headlineOf(event({ id: "3", text: "\n  first line  \nsecond" }))).toBe("first line");
  });

  it("falls back to the first text line and trims to 120 chars", () => {
    const long = "x".repeat(200);
    expect(headlineOf(event({ id: "4", eventType: "Handoff", text: long }))).toHaveLength(120);
    expect(headlineOf(event({ id: "5", text: null }))).toBe("Decision");
  });
});

describe("toMeaningfulItem", () => {
  it("returns null for non-meaningful event types", () => {
    expect(toMeaningfulItem(event({ id: "1", eventType: "PostToolUse" }), projects, new Set())).toBeNull();
  });

  it("maps a handoff with next action and open loops", () => {
    const item = toMeaningfulItem(
      event({ id: "h1", eventType: "Handoff", metadata: { contextSummary: "Wired it", nextAction: "Run tests", openLoops: ["a", "b"] } }),
      projects,
      new Set(["h1"]),
    );
    expect(item).toMatchObject({ kind: "handoff", eventType: "Handoff", headline: "Wired it", nextAction: "Run tests", openLoops: ["a", "b"], seen: true, projectName: "a" });
    expect(item?.href).toBe(eventHref("s1", "h1", projects[0].projectKey));
  });

  it("groups unresolved cwd under Unassigned", () => {
    expect(toMeaningfulItem(event({ id: "u1", cwd: "/elsewhere" }), projects, new Set())).toMatchObject({ projectKey: UNASSIGNED_KEY, projectName: "Unassigned" });
    expect(toMeaningfulItem(event({ id: "u2", cwd: null }), projects, new Set())).toMatchObject({ projectKey: UNASSIGNED_KEY });
  });
});

describe("pulseOf", () => {
  it("maps connection and recency to a pulse state", () => {
    expect(pulseOf("down", iso(0), NOW)).toBe("disconnected");
    expect(pulseOf("connecting", null, NOW)).toBe("connecting");
    expect(pulseOf("live", iso(30_000), NOW)).toBe("live");
    expect(pulseOf("live", iso(300_000), NOW)).toBe("idle");
    expect(pulseOf("live", null, NOW)).toBe("idle");
  });
});

describe("deriveModel", () => {
  it("builds project cards from live sessions and items, sorted by unseen then activity", () => {
    const model = deriveModel({
      now: NOW,
      connection: "live",
      lastEventAt: iso(5_000),
      projects,
      sessions: [
        { id: "s1", cwd: "/repo/a", lastSeenAt: iso(30_000) },
        { id: "s2", cwd: "/repo/b", lastSeenAt: iso(3_600_000) },
      ],
      events: [
        event({ id: "a1", cwd: "/repo/a", observedAt: iso(120_000) }),
        event({ id: "b1", cwd: "/repo/b", observedAt: iso(60_000), eventType: "Observation" }),
        event({ id: "b2", cwd: "/repo/b", observedAt: iso(30_000), eventType: "Handoff" }),
        event({ id: "b2", cwd: "/repo/b", observedAt: iso(30_000), eventType: "Handoff" }),
      ],
      seen: new Set(["a1"]),
    });
    expect(model.pulse).toBe("live");
    expect(model.unseenTotal).toBe(2);
    expect(model.projects.map((card) => card.name)).toEqual(["b", "a"]);
    expect(model.projects[0]).toMatchObject({ liveSessions: 0, unseen: 2, latest: expect.objectContaining({ id: "b2" }) });
    expect(model.projects[1]).toMatchObject({ liveSessions: 1, unseen: 0, lastActivityAt: iso(30_000) });
    expect(model.river.map((item) => item.id)).toEqual(["b2", "b1", "a1"]);
  });

  it("returns no cards when nothing is live and nothing is meaningful", () => {
    const model = deriveModel({ now: NOW, connection: "live", lastEventAt: null, projects, sessions: [], events: [], seen: new Set() });
    expect(model.projects).toEqual([]);
    expect(model.pulse).toBe("idle");
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/companion/model.test.ts`
Expected: FAIL (cannot resolve `./model` / `./links`).

- [ ] **Step 3: Implement links.ts**

```ts
// frontend/src/lib/companion/links.ts
// Per-event position link into the Black Box browse view. Mirrors StreamPage's private
// sessionHref so the companion never depends on StreamPage internals.
export function eventHref(sessionId: string, eventId: string, projectKey: string | null): string {
  const query = new URLSearchParams({ view: "browse", session: sessionId, event: eventId });
  if (projectKey) query.set("project", projectKey);
  return `/?${query.toString()}`;
}
```

- [ ] **Step 4: Implement model.ts**

```ts
// frontend/src/lib/companion/model.ts
import type { EventFeedItem, ProjectSummary } from "../api";
import { findProjectByIdentifier, projectShortName } from "../projects";
import { eventHref } from "./links";

export type PulseState = "connecting" | "live" | "idle" | "disconnected";
export type ConnectionState = "connecting" | "live" | "down";
export type MeaningfulKind = "decision" | "handoff" | "observation";
export type CompanionMode = "mini" | "compact" | "expanded";
export type ExpandedViewState = { kind: "project"; projectKey: string } | { kind: "river" };

export const MEANINGFUL_EVENT_TYPES: Readonly<Record<string, MeaningfulKind>> = {
  Decision: "decision",
  Handoff: "handoff",
  Observation: "observation",
};
export const MEANINGFUL_QUERY = "kind:decision OR kind:handoff OR kind:observation last:24h";
export const UNASSIGNED_KEY = "__unassigned__";
export const UNASSIGNED_NAME = "Unassigned";
export const LIVE_WINDOW_MS = 120_000;
export const ACTIVE_SESSION_WINDOW_MS = 10 * 60_000;
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

type Metadata = { decision?: unknown; contextSummary?: unknown; nextAction?: unknown; openLoops?: unknown };

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
  const raw = asString(meta.decision) ?? asString(meta.contextSummary) ?? asString(event.text) ?? event.eventType;
  const line = raw.split("\n").map((part) => part.trim()).find(Boolean) ?? event.eventType;
  return line.length > HEADLINE_MAX ? `${line.slice(0, HEADLINE_MAX - 1).trimEnd()}…` : line;
}

function projectFor(projects: ProjectSummary[], cwd: string | null | undefined): { key: string; name: string } {
  const project = findProjectByIdentifier(projects, cwd);
  return project ? { key: project.projectKey, name: projectShortName(project) } : { key: UNASSIGNED_KEY, name: UNASSIGNED_NAME };
}

export function toMeaningfulItem(event: EventFeedItem, projects: ProjectSummary[], seen: ReadonlySet<string>): MeaningfulItem | null {
  const kind = MEANINGFUL_EVENT_TYPES[event.eventType];
  if (!kind) return null;
  const meta = metadataOf(event);
  const project = projectFor(projects, event.cwd);
  const openLoops = Array.isArray(meta.openLoops) ? meta.openLoops.filter((loop): loop is string => typeof loop === "string") : [];
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

export function pulseOf(connection: ConnectionState, lastEventAt: string | null, now: number): PulseState {
  if (connection === "down") return "disconnected";
  if (connection === "connecting") return "connecting";
  return lastEventAt && now - timestamp(lastEventAt) <= LIVE_WINDOW_MS ? "live" : "idle";
}

export function deriveModel(input: DeriveInput): CompanionModel {
  const byId = new Map<string, MeaningfulItem>();
  for (const event of input.events) {
    const item = toMeaningfulItem(event, input.projects, input.seen);
    if (item) byId.set(item.id, item);
  }
  const items = [...byId.values()].sort((a, b) => timestamp(b.observedAt) - timestamp(a.observedAt));

  const cards = new Map<string, ProjectCard>();
  const ensure = (key: string, name: string): ProjectCard => {
    let card = cards.get(key);
    if (!card) {
      card = { key, name, liveSessions: 0, lastActivityAt: null, lastCaptureAt: null, unseen: 0, latest: null, items: [] };
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
    (a, b) => b.unseen - a.unseen || timestamp(b.lastActivityAt) - timestamp(a.lastActivityAt) || a.name.localeCompare(b.name),
  );

  return {
    pulse: pulseOf(input.connection, input.lastEventAt, input.now),
    lastEventAt: input.lastEventAt,
    unseenTotal: items.filter((item) => !item.seen).length,
    projects,
    river: items,
  };
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/companion/model.test.ts`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/companion/links.ts frontend/src/lib/companion/model.ts frontend/src/lib/companion/model.test.ts
git commit -m "Add Companion Derived Model"
```

---

### Task 2: Seen-state store

**Files:**
- Create: `frontend/src/lib/companion/seen.ts`
- Test: `frontend/src/lib/companion/seen.test.ts`

**Interfaces:**
- Produces: `createSeenStore(storage?: Storage | null): SeenStore` where `SeenStore = { seen: Accessor<ReadonlySet<string>>; markAll(ids: Iterable<string>): void }`; `safeStorage(): Storage | null`; constants `SEEN_STORAGE_KEY`, `SEEN_MAX`.

- [ ] **Step 1: Write the failing tests**

```ts
// frontend/src/lib/companion/seen.test.ts
import { describe, expect, it } from "vitest";
import { createSeenStore, SEEN_MAX, SEEN_STORAGE_KEY } from "./seen";

class MemoryStorage implements Storage {
  private map = new Map<string, string>();
  get length() { return this.map.size; }
  clear() { this.map.clear(); }
  getItem(key: string) { return this.map.get(key) ?? null; }
  key(index: number) { return [...this.map.keys()][index] ?? null; }
  removeItem(key: string) { this.map.delete(key); }
  setItem(key: string, value: string) { this.map.set(key, value); }
}

describe("createSeenStore", () => {
  it("works without storage", () => {
    const store = createSeenStore(null);
    expect(store.seen().has("a")).toBe(false);
    store.markAll(["a", "b"]);
    expect(store.seen().has("a")).toBe(true);
    expect(store.seen().size).toBe(2);
  });

  it("persists and reloads from storage", () => {
    const storage = new MemoryStorage();
    createSeenStore(storage).markAll(["x"]);
    expect(JSON.parse(storage.getItem(SEEN_STORAGE_KEY) ?? "[]")).toEqual(["x"]);
    expect(createSeenStore(storage).seen().has("x")).toBe(true);
  });

  it("ignores corrupt storage and keeps only the newest SEEN_MAX ids", () => {
    const storage = new MemoryStorage();
    storage.setItem(SEEN_STORAGE_KEY, "{not json");
    const store = createSeenStore(storage);
    expect(store.seen().size).toBe(0);
    store.markAll(Array.from({ length: SEEN_MAX + 10 }, (_, index) => `id-${index}`));
    expect(store.seen().size).toBe(SEEN_MAX);
    expect(store.seen().has("id-0")).toBe(false);
    expect(store.seen().has(`id-${SEEN_MAX + 9}`)).toBe(true);
  });

  it("does not notify when nothing new is marked", () => {
    const store = createSeenStore(null);
    store.markAll(["a"]);
    const before = store.seen();
    store.markAll(["a"]);
    expect(store.seen()).toBe(before);
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/companion/seen.test.ts`
Expected: FAIL (cannot resolve `./seen`).

- [ ] **Step 3: Implement seen.ts**

```ts
// frontend/src/lib/companion/seen.ts
import { createSignal, type Accessor } from "solid-js";

export const SEEN_STORAGE_KEY = "blackbox.companion.seen.v1";
export const SEEN_MAX = 2000;

export type SeenStore = {
  seen: Accessor<ReadonlySet<string>>;
  markAll(ids: Iterable<string>): void;
};

export function safeStorage(): Storage | null {
  try {
    const storage = window.localStorage;
    const probe = "__companion_probe__";
    storage.setItem(probe, "1");
    storage.removeItem(probe);
    return storage;
  } catch {
    return null;
  }
}

export function createSeenStore(storage: Storage | null = safeStorage()): SeenStore {
  const [seen, setSeen] = createSignal<ReadonlySet<string>>(load(storage));
  return {
    seen,
    markAll(ids) {
      const next = new Set(seen());
      let changed = false;
      for (const id of ids) {
        if (!next.has(id)) {
          next.add(id);
          changed = true;
        }
      }
      if (!changed) return;
      const trimmed = next.size > SEEN_MAX ? new Set([...next].slice(next.size - SEEN_MAX)) : next;
      setSeen(trimmed);
      persist(storage, trimmed);
    },
  };
}

function load(storage: Storage | null): Set<string> {
  if (!storage) return new Set();
  try {
    const parsed: unknown = JSON.parse(storage.getItem(SEEN_STORAGE_KEY) ?? "[]");
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === "string") : []);
  } catch {
    return new Set();
  }
}

function persist(storage: Storage | null, seen: ReadonlySet<string>): void {
  if (!storage) return;
  try {
    storage.setItem(SEEN_STORAGE_KEY, JSON.stringify([...seen]));
  } catch {
    // Storage full or blocked: the in-memory set still serves this page.
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/companion/seen.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/companion/seen.ts frontend/src/lib/companion/seen.test.ts
git commit -m "Add Companion Seen-State Store"
```

---

### Task 3: Shell bridge

**Files:**
- Create: `frontend/src/lib/companion/bridge.ts`
- Test: `frontend/src/lib/companion/bridge.test.ts`

**Interfaces:**
- Consumes: `PulseState`, `CompanionMode` from `./model`.
- Produces: `MODE_SIZES`, `ShellMessage`, `postToShell(message, target?): boolean`, `modeMessage(mode): ShellMessage`.

- [ ] **Step 1: Write the failing tests**

```ts
// frontend/src/lib/companion/bridge.test.ts
import { describe, expect, it, vi } from "vitest";
import { MODE_SIZES, modeMessage, postToShell } from "./bridge";

describe("postToShell", () => {
  it("returns false when no shell handler exists", () => {
    expect(postToShell({ type: "state", pulse: "live", unseen: 1 }, {})).toBe(false);
    expect(postToShell({ type: "state", pulse: "live", unseen: 1 }, undefined)).toBe(false);
  });

  it("posts to window.webkit.messageHandlers.companion when present", () => {
    const postMessage = vi.fn();
    const target = { webkit: { messageHandlers: { companion: { postMessage } } } };
    expect(postToShell(modeMessage("compact"), target)).toBe(true);
    expect(postMessage).toHaveBeenCalledWith({ type: "mode", mode: "compact", width: 340, height: 420 });
  });

  it("swallows handler errors", () => {
    const target = { webkit: { messageHandlers: { companion: { postMessage: () => { throw new Error("boom"); } } } } };
    expect(postToShell({ type: "state", pulse: "idle", unseen: 0 }, target)).toBe(false);
  });

  it("exposes the three panel sizes", () => {
    expect(MODE_SIZES).toEqual({ mini: { width: 132, height: 36 }, compact: { width: 340, height: 420 }, expanded: { width: 400, height: 560 } });
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/companion/bridge.test.ts`
Expected: FAIL (cannot resolve `./bridge`).

- [ ] **Step 3: Implement bridge.ts**

```ts
// frontend/src/lib/companion/bridge.ts
import type { CompanionMode, PulseState } from "./model";

export const MODE_SIZES: Readonly<Record<CompanionMode, { width: number; height: number }>> = {
  mini: { width: 132, height: 36 },
  compact: { width: 340, height: 420 },
  expanded: { width: 400, height: 560 },
};

export type ShellMessage =
  | { type: "state"; pulse: PulseState; unseen: number }
  | { type: "mode"; mode: CompanionMode; width: number; height: number };

type ShellTarget = { webkit?: { messageHandlers?: { companion?: { postMessage(message: unknown): void } } } };

export function postToShell(message: ShellMessage, target: unknown = typeof window === "undefined" ? undefined : window): boolean {
  const handler = (target as ShellTarget | undefined)?.webkit?.messageHandlers?.companion;
  if (!handler) return false;
  try {
    handler.postMessage(message);
    return true;
  } catch {
    return false;
  }
}

export function modeMessage(mode: CompanionMode): ShellMessage {
  return { type: "mode", mode, ...MODE_SIZES[mode] };
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/companion/bridge.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/companion/bridge.ts frontend/src/lib/companion/bridge.test.ts
git commit -m "Add Companion Shell Bridge"
```

---

### Task 4: Live store subscription and single-event fetch

**Files:**
- Modify: `frontend/src/lib/sse.ts` (add `onEventAppended`)
- Modify: `frontend/src/lib/api.ts` (add `getEvent`, after `getEventFeed` around line 594)
- Test: `frontend/src/lib/sse.test.ts`
- Test: `frontend/src/lib/api.getEvent.test.ts`

**Interfaces:**
- Produces: `LiveStore.onEventAppended(callback: (event: EventAppended) => void): () => void`; `getEvent(id: string): Promise<AgentEvent>`.

- [ ] **Step 1: Write the failing tests**

```ts
// frontend/src/lib/sse.test.ts
import { createRoot } from "solid-js";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createLiveStore } from "./sse";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, Array<(event: Event) => void>>();
  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }
  addEventListener(name: string, callback: (event: Event) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), callback]);
  }
  emit(name: string, data: unknown) {
    for (const callback of this.listeners.get(name) ?? []) callback(new MessageEvent(name, { data: JSON.stringify(data) }));
  }
  close() {}
}

describe("createLiveStore", () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("notifies event listeners and tracks status", () => {
    createRoot((dispose) => {
      const store = createLiveStore();
      const seen = vi.fn();
      store.onEventAppended(seen);
      const source = FakeEventSource.instances[0];
      expect(source.url).toBe("/api/stream");
      source.onopen?.();
      expect(store.status()).toBe("live");
      const payload = { id: "e1", sessionId: "s1", source: "claude", eventType: "Decision", observedAt: "2026-09-24T12:00:00Z" };
      source.emit("event.appended", payload);
      expect(seen).toHaveBeenCalledWith(payload);
      expect(store.events()[0]).toEqual(payload);
      source.onerror?.();
      expect(store.status()).toBe("down");
      dispose();
    });
  });

  it("stops notifying after unsubscribe", () => {
    createRoot((dispose) => {
      const store = createLiveStore();
      const seen = vi.fn();
      const stop = store.onEventAppended(seen);
      stop();
      FakeEventSource.instances[0].emit("event.appended", { id: "e2", sessionId: "s1", source: "codex", eventType: "Handoff", observedAt: "2026-09-24T12:00:00Z" });
      expect(seen).not.toHaveBeenCalled();
      dispose();
    });
  });
});
```

```ts
// frontend/src/lib/api.getEvent.test.ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { getEvent } from "./api";

describe("getEvent", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("fetches one event by id", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ id: "a/b", sessionId: "s", source: "claude", clientSessionId: "c", eventType: "Decision", observedAt: "2026-09-24T12:00:00Z" }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const event = await getEvent("a/b");
    expect(event.eventType).toBe("Decision");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/events/a%2Fb");
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/sse.test.ts src/lib/api.getEvent.test.ts`
Expected: FAIL (`onEventAppended is not a function`; `getEvent` is not exported).

- [ ] **Step 3: Extend sse.ts**

Change the `LiveStore` type and `createLiveStore` so both the no-`EventSource` fallback and the real store expose `onEventAppended`:

```ts
export type LiveStore = {
  status: () => LiveStatus;
  events: () => EventAppended[];
  onEventAppended: (callback: (event: EventAppended) => void) => () => void;
  onSessionUpdated: (callback: (event: SessionUpdated) => void) => () => void;
};
```

Inside `createLiveStore`, add `const eventListeners = new Set<(event: EventAppended) => void>();` next to `sessionListeners`, add to both returned objects:

```ts
    onEventAppended: (callback) => {
      eventListeners.add(callback);
      return () => eventListeners.delete(callback);
    },
```

and in the `event.appended` handler, after `setEvents(...)`:

```ts
    for (const listener of eventListeners) listener(payload);
```

- [ ] **Step 4: Add getEvent to api.ts** (directly after `getEventFeed`)

```ts
export function getEvent(id: string): Promise<AgentEvent> {
  return getJson(`/api/events/${encodeURIComponent(id)}`);
}
```

- [ ] **Step 5: Run the tests and the full unit suite**

Run: `cd frontend && npx vitest run src/lib/sse.test.ts src/lib/api.getEvent.test.ts && npm test`
Expected: PASS, no regressions (the existing suite does not construct `LiveStore` objects by hand; if a test fixture object literal fails type-checking under `tsc`, add the `onEventAppended: () => () => {}` member to that fixture).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/sse.ts frontend/src/lib/sse.test.ts frontend/src/lib/api.ts frontend/src/lib/api.getEvent.test.ts
git commit -m "Expose Event Subscriptions For The Companion"
```

---

### Task 5: Companion store (signals, loading, live merge, mode, seen)

**Files:**
- Create: `frontend/src/lib/companion/store.ts`
- Test: `frontend/src/lib/companion/store.test.ts`

**Interfaces:**
- Consumes: `LiveStore` (`status`, `onEventAppended`, `onSessionUpdated`), `deriveModel`, `createSeenStore`, `postToShell`, `modeMessage`, API functions.
- Produces: `createCompanionStore(live: LiveStore, deps?: CompanionDeps): CompanionStore` with `model`, `mode`, `expanded`, `loading`, `error`, `setMode`, `openProject`, `openRiver`, `toggleExpandedView`, `stepDown`, `refresh`; `defaultDeps()`; constants `MODE_STORAGE_KEY`, `TICK_MS`.

- [ ] **Step 1: Write the failing tests**

```ts
// frontend/src/lib/companion/store.test.ts
import { createRoot, createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import type { AgentEvent, AgentSession, EventFeedItem, ProjectSummary } from "../api";
import type { EventAppended, LiveStatus, LiveStore, SessionUpdated } from "../sse";
import { createSeenStore } from "./seen";
import { createCompanionStore, MODE_STORAGE_KEY, type CompanionDeps } from "./store";

const NOW = Date.parse("2026-09-24T12:00:00Z");
const iso = (offsetMs: number) => new Date(NOW - offsetMs).toISOString();

const projectA: ProjectSummary = {
  projectKey: "keyA",
  canonicalKey: "/repo/a",
  label: "/repo/a",
  sessionCount: 1,
  eventCount: 1,
  savedMeldCount: 0,
  firstSeenAt: iso(86_400_000),
  lastSeenAt: iso(0),
  scopes: [{ projectKey: "keyA", canonicalKey: "/repo/a", label: "/repo/a", primary: true }],
};

const sessionA: AgentSession = { id: "s1", source: "claude", clientSessionId: "c1", title: "t", cwd: "/repo/a", startedAt: iso(600_000), lastSeenAt: iso(20_000), eventCount: 5 };

const decision: EventFeedItem = { id: "d1", sessionId: "s1", source: "claude", clientSessionId: "c1", eventType: "Decision", text: "Pick A", cwd: "/repo/a", observedAt: iso(60_000) };

function fakeLive() {
  const [status, setStatus] = createSignal<LiveStatus>("connecting");
  const eventListeners = new Set<(event: EventAppended) => void>();
  const sessionListeners = new Set<(event: SessionUpdated) => void>();
  const live: LiveStore = {
    status,
    events: () => [],
    onEventAppended: (callback) => { eventListeners.add(callback); return () => eventListeners.delete(callback); },
    onSessionUpdated: (callback) => { sessionListeners.add(callback); return () => sessionListeners.delete(callback); },
  };
  return {
    live,
    setStatus,
    emitEvent: (event: EventAppended) => { for (const listener of eventListeners) listener(event); },
    emitSession: (event: SessionUpdated) => { for (const listener of sessionListeners) listener(event); },
  };
}

class MemoryStorage implements Storage {
  private map = new Map<string, string>();
  get length() { return this.map.size; }
  clear() { this.map.clear(); }
  getItem(key: string) { return this.map.get(key) ?? null; }
  key(index: number) { return [...this.map.keys()][index] ?? null; }
  removeItem(key: string) { this.map.delete(key); }
  setItem(key: string, value: string) { this.map.set(key, value); }
}

function deps(overrides: Partial<CompanionDeps> = {}): CompanionDeps {
  return {
    getProjects: vi.fn(async () => [projectA]),
    getSessions: vi.fn(async () => [sessionA]),
    getEventFeed: vi.fn(async () => ({ items: [decision] })),
    getEvent: vi.fn(async (id: string): Promise<AgentEvent> => ({ id, sessionId: "s1", source: "claude", clientSessionId: "c1", eventType: "Handoff", text: "Handoff to next-session: wired", metadata: { contextSummary: "wired", nextAction: "verify" }, observedAt: iso(1_000) })),
    seen: createSeenStore(null),
    storage: null,
    now: () => NOW,
    ...overrides,
  };
}

async function settled<T>(read: () => T, predicate: (value: T) => boolean): Promise<void> {
  await vi.waitFor(() => { if (!predicate(read())) throw new Error("not yet"); });
}

describe("createCompanionStore", () => {
  it("loads projects, sessions and meaningful events into one model", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus } = fakeLive();
      const store = createCompanionStore(live, deps());
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      expect(store.model().pulse).toBe("live");
      expect(store.model().projects).toHaveLength(1);
      expect(store.model().projects[0]).toMatchObject({ key: "keyA", liveSessions: 1, unseen: 1 });
      expect(store.mode()).toBe("mini");
      dispose();
    });
  });

  it("merges a live meaningful event by fetching its full body", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const d = deps();
      const store = createCompanionStore(live, d);
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      emitEvent({ id: "h1", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(1_000), cwd: "/repo/a" });
      await settled(() => store.model().river.length, (length) => length === 2);
      expect(d.getEvent).toHaveBeenCalledWith("h1");
      expect(store.model().river[0]).toMatchObject({ id: "h1", headline: "wired", nextAction: "verify", projectKey: "keyA" });
      expect(store.model().unseenTotal).toBe(2);
      dispose();
    });
  });

  it("ignores non-meaningful events except for liveness", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const d = deps();
      const store = createCompanionStore(live, d);
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      emitEvent({ id: "t1", sessionId: "s9", source: "codex", eventType: "PostToolUse", observedAt: iso(0), cwd: "/repo/a" });
      expect(d.getEvent).not.toHaveBeenCalled();
      expect(store.model().projects[0].liveSessions).toBe(2);
      expect(store.model().lastEventAt).toBe(iso(0));
      dispose();
    });
  });

  it("marks items seen when a project is opened and when items arrive while it is open", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const store = createCompanionStore(live, deps());
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      store.openProject("keyA");
      expect(store.mode()).toBe("expanded");
      expect(store.expanded()).toEqual({ kind: "project", projectKey: "keyA" });
      expect(store.model().unseenTotal).toBe(0);
      emitEvent({ id: "h2", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(500), cwd: "/repo/a" });
      await settled(() => store.model().river.length, (length) => length === 2);
      expect(store.model().unseenTotal).toBe(0);
      dispose();
    });
  });

  it("refetches after reconnect", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus } = fakeLive();
      const d = deps();
      const store = createCompanionStore(live, d);
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      setStatus("down");
      expect(store.model().pulse).toBe("disconnected");
      expect(store.model().projects).toHaveLength(1);
      setStatus("live");
      await settled(() => (d.getEventFeed as ReturnType<typeof vi.fn>).mock.calls.length, (calls) => calls === 2);
      dispose();
    });
  });

  it("persists mode and expanded view, and survives a throwing storage", async () => {
    await createRoot(async (dispose) => {
      const storage = new MemoryStorage();
      const { live } = fakeLive();
      const store = createCompanionStore(live, deps({ storage }));
      await settled(store.loading, (loading) => !loading);
      store.openRiver();
      expect(JSON.parse(storage.getItem(MODE_STORAGE_KEY) ?? "{}")).toEqual({ mode: "expanded", expanded: { kind: "river" } });
      const reloaded = createCompanionStore(fakeLive().live, deps({ storage }));
      expect(reloaded.mode()).toBe("expanded");
      expect(reloaded.expanded()).toEqual({ kind: "river" });

      const throwing = new MemoryStorage();
      throwing.setItem = () => { throw new Error("blocked"); };
      const guarded = createCompanionStore(fakeLive().live, deps({ storage: throwing }));
      guarded.setMode("compact");
      expect(guarded.mode()).toBe("compact");
      dispose();
    });
  });

  it("steps down one level at a time", async () => {
    await createRoot(async (dispose) => {
      const store = createCompanionStore(fakeLive().live, deps());
      await settled(store.loading, (loading) => !loading);
      store.openRiver();
      store.stepDown();
      expect(store.mode()).toBe("compact");
      store.stepDown();
      expect(store.mode()).toBe("mini");
      store.stepDown();
      expect(store.mode()).toBe("mini");
      dispose();
    });
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/companion/store.test.ts`
Expected: FAIL (cannot resolve `./store`).

- [ ] **Step 3: Implement store.ts**

```ts
// frontend/src/lib/companion/store.ts
import { createEffect, createMemo, createSignal, on, onCleanup, type Accessor } from "solid-js";
import { getEvent, getEventFeed, getProjects, getSessions, type AgentEvent, type AgentSession, type EventFeedItem, type ProjectSummary } from "../api";
import type { EventAppended, LiveStore, SessionUpdated } from "../sse";
import { modeMessage, postToShell } from "./bridge";
import { deriveModel, MEANINGFUL_EVENT_TYPES, MEANINGFUL_QUERY, type CompanionMode, type CompanionModel, type ExpandedViewState, type SessionLiveness } from "./model";
import { createSeenStore, safeStorage, type SeenStore } from "./seen";

export const MODE_STORAGE_KEY = "blackbox.companion.mode.v1";
export const TICK_MS = 30_000;

export type CompanionDeps = {
  getProjects: () => Promise<ProjectSummary[]>;
  getSessions: (limit: number, includeChildren: boolean) => Promise<AgentSession[]>;
  getEventFeed: (params: { q: string; limit: number }) => Promise<{ items: EventFeedItem[] }>;
  getEvent: (id: string) => Promise<AgentEvent>;
  seen: SeenStore;
  storage: Storage | null;
  now: () => number;
};

export type CompanionStore = {
  model: Accessor<CompanionModel>;
  mode: Accessor<CompanionMode>;
  expanded: Accessor<ExpandedViewState>;
  loading: Accessor<boolean>;
  error: Accessor<string | null>;
  setMode(mode: CompanionMode): void;
  openProject(projectKey: string): void;
  openRiver(): void;
  toggleExpandedView(): void;
  stepDown(): void;
  refresh(): Promise<void>;
};

export function defaultDeps(): CompanionDeps {
  const storage = safeStorage();
  return { getProjects, getSessions, getEventFeed, getEvent, seen: createSeenStore(storage), storage, now: () => Date.now() };
}

type PersistedMode = { mode: CompanionMode; expanded: ExpandedViewState };
const MODES: CompanionMode[] = ["mini", "compact", "expanded"];

function timestamp(iso: string | null | undefined): number {
  const value = iso ? Date.parse(iso) : Number.NaN;
  return Number.isNaN(value) ? 0 : value;
}

function loadMode(storage: Storage | null): PersistedMode {
  const fallback: PersistedMode = { mode: "mini", expanded: { kind: "river" } };
  if (!storage) return fallback;
  try {
    const parsed = JSON.parse(storage.getItem(MODE_STORAGE_KEY) ?? "null") as { mode?: unknown; expanded?: { kind?: unknown; projectKey?: unknown } } | null;
    if (!parsed || !MODES.includes(parsed.mode as CompanionMode)) return fallback;
    const expanded: ExpandedViewState =
      parsed.expanded?.kind === "project" && typeof parsed.expanded.projectKey === "string"
        ? { kind: "project", projectKey: parsed.expanded.projectKey }
        : { kind: "river" };
    return { mode: parsed.mode as CompanionMode, expanded };
  } catch {
    return fallback;
  }
}

function persistMode(storage: Storage | null, value: PersistedMode): void {
  if (!storage) return;
  try {
    storage.setItem(MODE_STORAGE_KEY, JSON.stringify(value));
  } catch {
    // Blocked storage: mode still lives in memory for this page.
  }
}

export function createCompanionStore(live: LiveStore, deps: CompanionDeps = defaultDeps()): CompanionStore {
  const [projects, setProjects] = createSignal<ProjectSummary[]>([]);
  const [sessions, setSessions] = createSignal<Map<string, SessionLiveness>>(new Map(), { equals: false });
  const [events, setEvents] = createSignal<Map<string, EventFeedItem>>(new Map(), { equals: false });
  const [lastEventAt, setLastEventAt] = createSignal<string | null>(null);
  const [now, setNow] = createSignal(deps.now());
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<string | null>(null);
  const persisted = loadMode(deps.storage);
  const [mode, setModeSignal] = createSignal<CompanionMode>(persisted.mode);
  const [expanded, setExpanded] = createSignal<ExpandedViewState>(persisted.expanded);
  const [lastProjectKey, setLastProjectKey] = createSignal<string | null>(persisted.expanded.kind === "project" ? persisted.expanded.projectKey : null);

  const model = createMemo(() =>
    deriveModel({
      now: now(),
      connection: live.status(),
      lastEventAt: lastEventAt(),
      projects: projects(),
      sessions: [...sessions().values()],
      events: [...events().values()],
      seen: deps.seen.seen(),
    }),
  );

  function bumpLastEvent(iso: string | null | undefined): void {
    if (iso && timestamp(iso) > timestamp(lastEventAt())) setLastEventAt(iso);
  }

  function upsertSession(next: SessionLiveness): void {
    setSessions((map) => {
      const current = map.get(next.id);
      if (!current || timestamp(next.lastSeenAt) >= timestamp(current.lastSeenAt)) {
        map.set(next.id, { ...next, cwd: next.cwd ?? current?.cwd ?? null });
      }
      return map;
    });
  }

  function addEvent(item: EventFeedItem): void {
    setEvents((map) => {
      map.set(item.id, item);
      return map;
    });
    const view = expanded();
    if (mode() !== "expanded") return;
    const card = model().projects.find((project) => project.items.some((entry) => entry.id === item.id));
    if (!card) return;
    if (view.kind === "river" || view.projectKey === card.key) deps.seen.markAll([item.id]);
  }

  async function refresh(): Promise<void> {
    setLoading(true);
    try {
      const [projectList, sessionList, feed] = await Promise.all([
        deps.getProjects(),
        deps.getSessions(250, true),
        deps.getEventFeed({ q: MEANINGFUL_QUERY, limit: 200 }),
      ]);
      setProjects(projectList);
      for (const session of sessionList) {
        upsertSession({ id: session.id, cwd: session.cwd ?? null, lastSeenAt: session.lastSeenAt });
        bumpLastEvent(session.lastSeenAt);
      }
      setEvents((map) => {
        for (const item of feed.items) map.set(item.id, item);
        return map;
      });
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  const stopEvents = live.onEventAppended((event: EventAppended) => {
    bumpLastEvent(event.observedAt);
    upsertSession({ id: event.sessionId, cwd: event.cwd ?? null, lastSeenAt: event.observedAt });
    if (!(event.eventType in MEANINGFUL_EVENT_TYPES)) return;
    void deps
      .getEvent(event.id)
      .then((full) => addEvent({ ...full, cwd: event.cwd ?? null, sessionTitle: event.title ?? null }))
      .catch(() => {
        // The next refresh (reconnect or reload) backfills anything missed here.
      });
  });
  const stopSessions = live.onSessionUpdated((event: SessionUpdated) => {
    if (!event.lastSeenAt) return;
    upsertSession({ id: event.sessionId, cwd: event.cwd ?? null, lastSeenAt: event.lastSeenAt });
    bumpLastEvent(event.lastSeenAt);
  });
  const timer = setInterval(() => setNow(deps.now()), TICK_MS);
  onCleanup(() => {
    stopEvents();
    stopSessions();
    clearInterval(timer);
  });

  createEffect(
    on(
      live.status,
      (status, previous) => {
        if (status === "live" && previous === "down") void refresh();
      },
      { defer: true },
    ),
  );
  createEffect(() => {
    const current = model();
    postToShell({ type: "state", pulse: current.pulse, unseen: current.unseenTotal });
  });
  createEffect(() => {
    postToShell(modeMessage(mode()));
  });
  createEffect(() => {
    persistMode(deps.storage, { mode: mode(), expanded: expanded() });
  });

  function openProject(projectKey: string): void {
    setExpanded({ kind: "project", projectKey });
    setLastProjectKey(projectKey);
    setModeSignal("expanded");
    const card = model().projects.find((project) => project.key === projectKey);
    if (card) deps.seen.markAll(card.items.map((item) => item.id));
  }

  function openRiver(): void {
    setExpanded({ kind: "river" });
    setModeSignal("expanded");
    deps.seen.markAll(model().river.map((item) => item.id));
  }

  function toggleExpandedView(): void {
    if (expanded().kind === "river") {
      const key = lastProjectKey() ?? model().projects[0]?.key;
      if (key) openProject(key);
      return;
    }
    openRiver();
  }

  function stepDown(): void {
    if (mode() === "expanded") setModeSignal("compact");
    else if (mode() === "compact") setModeSignal("mini");
  }

  void refresh();

  return { model, mode, expanded, loading, error, setMode: setModeSignal, openProject, openRiver, toggleExpandedView, stepDown, refresh };
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/companion/store.test.ts`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/companion/store.ts frontend/src/lib/companion/store.test.ts
git commit -m "Add Companion Store"
```

---

### Task 6: Components, page, route, bare shell, styles

**Files:**
- Create: `frontend/src/components/companion/MiniChip.tsx`
- Create: `frontend/src/components/companion/CompactList.tsx`
- Create: `frontend/src/components/companion/ExpandedView.tsx`
- Create: `frontend/src/pages/CompanionPage.tsx`
- Create: `frontend/src/companion.css`
- Modify: `frontend/src/index.tsx` (import + one `<Route>`)
- Modify: `frontend/src/App.tsx:1,22-24,40-44,107-110` (bare mode)
- Test: `frontend/src/components/companion/MiniChip.test.tsx`
- Test: `frontend/src/components/companion/CompactList.test.tsx`
- Test: `frontend/src/components/companion/ExpandedView.test.tsx`
- Test: `frontend/src/pages/CompanionPage.test.tsx`

**Interfaces:**
- Consumes: `CompanionStore` from Task 5, `CompanionModel`/`ExpandedViewState`/`PulseState` from Task 1, `KindBadge` (`components/KindBadge.tsx`, props `{ kind?: string | null; label?: string }`), `timeAgo` (`lib/format.ts`), `useLiveStore` (`lib/sse.ts`).
- Produces: default exports `MiniChip`, `CompactList`, `ExpandedView`, `CompanionPage`; named `PULSE_LABEL`, `pulseText`.

- [ ] **Step 1: Write the failing component tests**

```tsx
// frontend/src/components/companion/MiniChip.test.tsx
import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import MiniChip from "./MiniChip";

describe("MiniChip", () => {
  it("shows pulse and unseen count and expands on click", () => {
    const onExpand = vi.fn();
    render(() => <MiniChip pulse="live" unseen={3} onExpand={onExpand} />);
    const chip = screen.getByRole("button", { name: "Black Box companion: live, 3 unseen" });
    expect(chip).toHaveClass("companion-chip--live");
    expect(screen.getByText("3")).toBeInTheDocument();
    fireEvent.click(chip);
    expect(onExpand).toHaveBeenCalledTimes(1);
  });

  it("hides the count at zero and labels offline", () => {
    render(() => <MiniChip pulse="disconnected" unseen={0} onExpand={() => {}} />);
    expect(screen.getByText("offline")).toBeInTheDocument();
    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });
});
```

```tsx
// frontend/src/components/companion/CompactList.test.tsx
import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { CompanionModel, MeaningfulItem } from "../../lib/companion/model";
import CompactList from "./CompactList";

const item: MeaningfulItem = { id: "d1", kind: "decision", eventType: "Decision", projectKey: "keyA", projectName: "a", sessionId: "s1", source: "claude", headline: "Pick A", nextAction: null, openLoops: [], observedAt: new Date(Date.now() - 60_000).toISOString(), href: "/?view=browse&session=s1&event=d1", seen: false };

const model: CompanionModel = {
  pulse: "live",
  lastEventAt: new Date(Date.now() - 5_000).toISOString(),
  unseenTotal: 1,
  projects: [{ key: "keyA", name: "a", liveSessions: 2, lastActivityAt: item.observedAt, lastCaptureAt: item.observedAt, unseen: 1, latest: item, items: [item] }],
  river: [item],
};

describe("CompactList", () => {
  it("lists projects with live count, unseen badge and latest headline", () => {
    const onOpenProject = vi.fn();
    render(() => <CompactList model={model} onOpenProject={onOpenProject} onOpenRiver={() => {}} onCollapse={() => {}} />);
    const row = screen.getByRole("button", { name: "a: 1 unseen, 2 live" });
    expect(row).toHaveTextContent("2 live");
    expect(row).toHaveTextContent("Pick A");
    fireEvent.click(row);
    expect(onOpenProject).toHaveBeenCalledWith("keyA");
    expect(screen.getByText(/Live · last event/)).toBeInTheDocument();
  });

  it("shows the quiet empty state and the river and collapse controls", () => {
    const onOpenRiver = vi.fn();
    const onCollapse = vi.fn();
    render(() => <CompactList model={{ ...model, projects: [], river: [], unseenTotal: 0, pulse: "idle" }} onOpenProject={() => {}} onOpenRiver={onOpenRiver} onCollapse={onCollapse} />);
    expect(screen.getByText("Quiet. No active projects in the last 24h.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "River" }));
    fireEvent.click(screen.getByRole("button", { name: "Collapse" }));
    expect(onOpenRiver).toHaveBeenCalledTimes(1);
    expect(onCollapse).toHaveBeenCalledTimes(1);
  });
});
```

```tsx
// frontend/src/components/companion/ExpandedView.test.tsx
import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { CompanionModel, MeaningfulItem } from "../../lib/companion/model";
import ExpandedView from "./ExpandedView";

const handoff: MeaningfulItem = { id: "h1", kind: "handoff", eventType: "Handoff", projectKey: "keyA", projectName: "a", sessionId: "s1", source: "codex", headline: "Wired the bridge", nextAction: "Run the e2e suite", openLoops: ["restart service"], observedAt: new Date(Date.now() - 120_000).toISOString(), href: "/?view=browse&session=s1&event=h1&project=keyA", seen: false };
const decision: MeaningfulItem = { ...handoff, id: "d1", kind: "decision", eventType: "Decision", projectKey: "keyB", projectName: "b", headline: "Pick B", nextAction: null, openLoops: [], href: "/?view=browse&session=s1&event=d1&project=keyB", seen: true };

const model: CompanionModel = {
  pulse: "idle",
  lastEventAt: null,
  unseenTotal: 1,
  projects: [
    { key: "keyA", name: "a", liveSessions: 1, lastActivityAt: handoff.observedAt, lastCaptureAt: handoff.observedAt, unseen: 1, latest: handoff, items: [handoff] },
    { key: "keyB", name: "b", liveSessions: 0, lastActivityAt: decision.observedAt, lastCaptureAt: decision.observedAt, unseen: 0, latest: decision, items: [decision] },
  ],
  river: [handoff, decision],
};

describe("ExpandedView", () => {
  it("renders one project's items with links, next action and open loops", () => {
    const onBack = vi.fn();
    render(() => <ExpandedView model={model} view={{ kind: "project", projectKey: "keyA" }} onBack={onBack} onToggleView={() => {}} onCollapse={() => {}} />);
    expect(screen.getByText("a")).toBeInTheDocument();
    expect(screen.getByText(/1 live/)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /Wired the bridge/ });
    expect(link).toHaveAttribute("href", "/?view=browse&session=s1&event=h1&project=keyA");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveClass("companion-item--unseen");
    expect(screen.getByText("Next: Run the e2e suite")).toBeInTheDocument();
    expect(screen.getByText("1 open loop")).toBeInTheDocument();
    expect(screen.queryByText("Pick B")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Back to projects" }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it("renders the river across projects with a project prefix and a toggle", () => {
    const onToggleView = vi.fn();
    render(() => <ExpandedView model={model} view={{ kind: "river" }} onBack={() => {}} onToggleView={onToggleView} onCollapse={() => {}} />);
    expect(screen.getByText("River")).toBeInTheDocument();
    expect(screen.getAllByRole("link")).toHaveLength(2);
    expect(screen.getByText("b")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "By project" }));
    expect(onToggleView).toHaveBeenCalledTimes(1);
  });
});
```

```tsx
// frontend/src/pages/CompanionPage.test.tsx
import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { LiveStoreContext, type LiveStatus, type LiveStore } from "../lib/sse";
import CompanionPage from "./CompanionPage";

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  const now = Date.now();
  return {
    ...actual,
    getProjects: vi.fn(async () => [{ projectKey: "keyA", canonicalKey: "/repo/a", label: "/repo/a", sessionCount: 1, eventCount: 1, savedMeldCount: 0, firstSeenAt: new Date(now - 86_400_000).toISOString(), lastSeenAt: new Date(now).toISOString(), scopes: [{ projectKey: "keyA", canonicalKey: "/repo/a", label: "/repo/a", primary: true }] }]),
    getSessions: vi.fn(async () => [{ id: "s1", source: "claude", clientSessionId: "c1", title: "t", cwd: "/repo/a", startedAt: new Date(now - 60_000).toISOString(), lastSeenAt: new Date(now - 300_000).toISOString(), eventCount: 3 }]),
    getEventFeed: vi.fn(async () => ({ limit: 200, count: 1, items: [{ id: "d1", sessionId: "s1", source: "claude", clientSessionId: "c1", eventType: "Decision", text: "Pick A", cwd: "/repo/a", observedAt: new Date(now - 30_000).toISOString() }] })),
    getEvent: vi.fn(),
  };
});

function fakeLive(): LiveStore {
  const [status] = createSignal<LiveStatus>("live");
  return { status, events: () => [], onEventAppended: () => () => {}, onSessionUpdated: () => () => {} };
}

describe("CompanionPage", () => {
  it("discloses mini, compact, expanded and steps down with Escape", async () => {
    window.localStorage.clear();
    render(() => (
      <LiveStoreContext.Provider value={fakeLive()}>
        <CompanionPage />
      </LiveStoreContext.Provider>
    ));
    const chip = await screen.findByRole("button", { name: /Black Box companion/ });
    await waitFor(() => expect(chip).toHaveAccessibleName("Black Box companion: idle, 1 unseen"));
    fireEvent.click(chip);
    const row = await screen.findByRole("button", { name: "a: 1 unseen, 1 live" });
    fireEvent.click(row);
    expect(await screen.findByRole("link", { name: /Pick A/ })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(await screen.findByRole("button", { name: "a: 0 unseen, 1 live" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(await screen.findByRole("button", { name: "Black Box companion: idle, 0 unseen" })).toBeInTheDocument();
  });
});
```

Note on the page test: the fake live store reports `live` but no `lastEventAt` newer than 120 s arrives from the fixtures, so the pulse reads `idle`; that is the expected label.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/components/companion src/pages/CompanionPage.test.tsx`
Expected: FAIL (modules not found).

- [ ] **Step 3: Implement MiniChip.tsx**

```tsx
// frontend/src/components/companion/MiniChip.tsx
import { Show } from "solid-js";
import type { PulseState } from "../../lib/companion/model";

export const PULSE_LABEL: Record<PulseState, string> = { connecting: "connecting", live: "live", idle: "idle", disconnected: "offline" };

type MiniChipProps = { pulse: PulseState; unseen: number; onExpand: () => void };

export default function MiniChip(props: MiniChipProps) {
  return (
    <button
      type="button"
      class={`companion-chip companion-chip--${props.pulse}`}
      aria-label={`Black Box companion: ${props.pulse}, ${props.unseen} unseen`}
      title="Expand"
      onClick={() => props.onExpand()}
    >
      <span class="companion-dot" aria-hidden="true" />
      <span class="companion-chip-label">{PULSE_LABEL[props.pulse]}</span>
      <Show when={props.unseen > 0}>
        <span class="companion-count">{props.unseen}</span>
      </Show>
    </button>
  );
}
```

- [ ] **Step 4: Implement CompactList.tsx**

```tsx
// frontend/src/components/companion/CompactList.tsx
import { For, Show } from "solid-js";
import type { CompanionModel } from "../../lib/companion/model";
import { timeAgo } from "../../lib/format";
import KindBadge from "../KindBadge";

type CompactListProps = {
  model: CompanionModel;
  onOpenProject: (projectKey: string) => void;
  onOpenRiver: () => void;
  onCollapse: () => void;
};

export function pulseText(model: CompanionModel): string {
  switch (model.pulse) {
    case "disconnected":
      return "Disconnected from Black Box";
    case "connecting":
      return "Connecting…";
    case "live":
      return `Live · last event ${timeAgo(model.lastEventAt)} ago`;
    default:
      return model.lastEventAt ? `Idle · last event ${timeAgo(model.lastEventAt)} ago` : "Idle";
  }
}

export default function CompactList(props: CompactListProps) {
  return (
    <div class="companion-panel">
      <header class="companion-header">
        <span class="companion-title">Projects</span>
        <button type="button" class="companion-link-button" onClick={() => props.onOpenRiver()}>
          River
        </button>
        <button type="button" class="companion-icon-button" aria-label="Collapse" onClick={() => props.onCollapse()}>
          –
        </button>
      </header>
      <Show when={props.model.projects.length > 0} fallback={<p class="companion-empty">Quiet. No active projects in the last 24h.</p>}>
        <ul class="companion-projects">
          <For each={props.model.projects}>
            {(card) => (
              <li>
                <button
                  type="button"
                  class="companion-project-row"
                  aria-label={`${card.name}: ${card.unseen} unseen, ${card.liveSessions} live`}
                  onClick={() => props.onOpenProject(card.key)}
                >
                  <span class={`companion-live-dot${card.liveSessions > 0 ? " companion-live-dot--on" : ""}`} aria-hidden="true" />
                  <span class="companion-project-name">{card.name}</span>
                  <span class="companion-project-meta">{card.liveSessions > 0 ? `${card.liveSessions} live` : "quiet"}</span>
                  <Show when={card.unseen > 0}>
                    <span class="companion-count">{card.unseen}</span>
                  </Show>
                  <Show when={card.latest}>
                    {(latest) => (
                      <span class="companion-project-latest">
                        <KindBadge kind={latest().eventType} />
                        <span class="companion-project-headline">{latest().headline}</span>
                        <span class="companion-age">{timeAgo(latest().observedAt)}</span>
                      </span>
                    )}
                  </Show>
                </button>
              </li>
            )}
          </For>
        </ul>
      </Show>
      <footer class={`companion-footer companion-footer--${props.model.pulse}`}>{pulseText(props.model)}</footer>
    </div>
  );
}
```

- [ ] **Step 5: Implement ExpandedView.tsx**

```tsx
// frontend/src/components/companion/ExpandedView.tsx
import { createMemo, For, Show } from "solid-js";
import type { CompanionModel, ExpandedViewState } from "../../lib/companion/model";
import { timeAgo } from "../../lib/format";
import KindBadge from "../KindBadge";

type ExpandedViewProps = {
  model: CompanionModel;
  view: ExpandedViewState;
  onBack: () => void;
  onToggleView: () => void;
  onCollapse: () => void;
};

function ago(iso: string | null): string {
  return iso ? `${timeAgo(iso)} ago` : "none";
}

export default function ExpandedView(props: ExpandedViewProps) {
  const card = createMemo(() => (props.view.kind === "project" ? props.model.projects.find((project) => project.key === props.view.projectKey) ?? null : null));
  const items = createMemo(() => (props.view.kind === "river" ? props.model.river : card()?.items ?? []));
  const title = () => (props.view.kind === "river" ? "River" : card()?.name ?? "Project");

  return (
    <div class="companion-panel companion-panel--expanded">
      <header class="companion-header">
        <button type="button" class="companion-icon-button" aria-label="Back to projects" onClick={() => props.onBack()}>
          ‹
        </button>
        <span class="companion-title">{title()}</span>
        <button type="button" class="companion-link-button" onClick={() => props.onToggleView()}>
          {props.view.kind === "river" ? "By project" : "River"}
        </button>
        <button type="button" class="companion-icon-button" aria-label="Collapse" onClick={() => props.onCollapse()}>
          –
        </button>
      </header>
      <Show when={card()}>
        {(current) => (
          <p class="companion-strip">
            {current().liveSessions} live · activity {ago(current().lastActivityAt)} · capture {ago(current().lastCaptureAt)}
          </p>
        )}
      </Show>
      <Show when={items().length > 0} fallback={<p class="companion-empty">Nothing meaningful in the last 24h.</p>}>
        <ul class="companion-items">
          <For each={items()}>
            {(item) => (
              <li>
                <a class={`companion-item${item.seen ? "" : " companion-item--unseen"}`} href={item.href} target="_blank" rel="noreferrer">
                  <span class="companion-item-head">
                    <KindBadge kind={item.eventType} />
                    <Show when={props.view.kind === "river"}>
                      <span class="companion-item-project">{item.projectName}</span>
                    </Show>
                    <span class="companion-age">
                      {item.source} · {timeAgo(item.observedAt)}
                    </span>
                  </span>
                  <span class="companion-item-headline">{item.headline}</span>
                  <Show when={item.nextAction}>
                    <span class="companion-next">Next: {item.nextAction}</span>
                  </Show>
                  <Show when={item.openLoops.length > 0}>
                    <span class="companion-loops">
                      {item.openLoops.length} open loop{item.openLoops.length === 1 ? "" : "s"}
                    </span>
                  </Show>
                </a>
              </li>
            )}
          </For>
        </ul>
      </Show>
    </div>
  );
}
```

- [ ] **Step 6: Implement CompanionPage.tsx**

```tsx
// frontend/src/pages/CompanionPage.tsx
import { Match, onCleanup, onMount, Show, Switch } from "solid-js";
import CompactList from "../components/companion/CompactList";
import ExpandedView from "../components/companion/ExpandedView";
import MiniChip from "../components/companion/MiniChip";
import { createCompanionStore } from "../lib/companion/store";
import { useLiveStore } from "../lib/sse";
import "../companion.css";

export default function CompanionPage() {
  const live = useLiveStore();
  const store = createCompanionStore(live);

  onMount(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === "Escape") store.stepDown();
    };
    window.addEventListener("keydown", handler);
    onCleanup(() => window.removeEventListener("keydown", handler));
  });

  return (
    <section class={`companion companion--${store.mode()}`} data-mode={store.mode()}>
      <Switch>
        <Match when={store.mode() === "mini"}>
          <MiniChip pulse={store.model().pulse} unseen={store.model().unseenTotal} onExpand={() => store.setMode("compact")} />
        </Match>
        <Match when={store.mode() === "compact"}>
          <CompactList model={store.model()} onOpenProject={store.openProject} onOpenRiver={store.openRiver} onCollapse={() => store.setMode("mini")} />
        </Match>
        <Match when={store.mode() === "expanded"}>
          <ExpandedView model={store.model()} view={store.expanded()} onBack={() => store.setMode("compact")} onToggleView={store.toggleExpandedView} onCollapse={() => store.setMode("mini")} />
        </Match>
      </Switch>
      <Show when={store.error()}>{(message) => <p class="companion-error" role="status">{message()}</p>}</Show>
    </section>
  );
}
```

- [ ] **Step 7: Add companion.css**

```css
/* frontend/src/companion.css — companion surface only. Uses theme tokens when present. */
.app-shell--bare {
  background: transparent;
  min-height: 0;
}
.app-shell--bare .app-main {
  padding: 0;
  max-width: none;
}
.companion {
  font: 13px/1.35 var(--font-ui, system-ui, -apple-system, sans-serif);
  color: var(--fg, #e6e6e6);
}
.companion--mini {
  display: inline-block;
}
.companion-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--border, #2a2a2a);
  border-radius: 18px;
  background: var(--bg-elevated, #161616);
  color: inherit;
  cursor: pointer;
}
.companion-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #6b6b6b;
}
.companion-chip--live .companion-dot { background: #26c054; }
.companion-chip--idle .companion-dot { background: #8a8a8a; }
.companion-chip--connecting .companion-dot { background: #e0b341; }
.companion-chip--disconnected .companion-dot { background: #e5484d; }
.companion-count {
  min-width: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--accent, #8077e6);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  text-align: center;
}
.companion-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  min-height: 0;
  border: 1px solid var(--border, #2a2a2a);
  border-radius: 12px;
  background: var(--bg-elevated, #161616);
  overflow: hidden;
}
.companion-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border, #2a2a2a);
}
.companion-title {
  flex: 1;
  font-weight: 600;
}
.companion-link-button,
.companion-icon-button {
  border: 0;
  background: transparent;
  color: var(--fg-muted, #a0a0a0);
  cursor: pointer;
  font: inherit;
}
.companion-icon-button { width: 24px; height: 24px; border-radius: 6px; }
.companion-link-button:hover,
.companion-icon-button:hover { color: var(--fg, #e6e6e6); background: var(--bg-hover, #222); }
.companion-projects,
.companion-items {
  flex: 1;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  overflow-y: auto;
}
.companion-project-row {
  display: grid;
  grid-template-columns: 10px 1fr auto auto;
  grid-template-areas: "dot name meta count" "dot latest latest latest";
  gap: 2px 8px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  font: inherit;
}
.companion-project-row:hover { background: var(--bg-hover, #222); }
.companion-live-dot { grid-area: dot; align-self: center; width: 8px; height: 8px; border-radius: 50%; background: #3a3a3a; }
.companion-live-dot--on { background: #26c054; }
.companion-project-name { grid-area: name; font-weight: 600; }
.companion-project-meta { grid-area: meta; color: var(--fg-muted, #a0a0a0); font-size: 12px; }
.companion-project-row .companion-count { grid-area: count; }
.companion-project-latest { grid-area: latest; display: flex; gap: 6px; align-items: baseline; color: var(--fg-muted, #a0a0a0); font-size: 12px; min-width: 0; }
.companion-project-headline { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.companion-age { margin-left: auto; color: var(--fg-muted, #a0a0a0); font-size: 11px; white-space: nowrap; }
.companion-strip {
  margin: 0;
  padding: 6px 10px;
  color: var(--fg-muted, #a0a0a0);
  font-size: 12px;
  border-bottom: 1px solid var(--border, #2a2a2a);
}
.companion-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  color: inherit;
  text-decoration: none;
  border-left: 3px solid transparent;
}
.companion-item:hover { background: var(--bg-hover, #222); }
.companion-item--unseen { border-left-color: var(--accent, #8077e6); }
.companion-item-head { display: flex; gap: 6px; align-items: baseline; }
.companion-item-project { font-weight: 600; font-size: 12px; }
.companion-item-headline { overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.companion-next { color: var(--fg, #e6e6e6); font-size: 12px; }
.companion-loops { color: var(--fg-muted, #a0a0a0); font-size: 11px; }
.companion-empty { margin: 0; padding: 16px 12px; color: var(--fg-muted, #a0a0a0); }
.companion-footer {
  padding: 6px 10px;
  border-top: 1px solid var(--border, #2a2a2a);
  color: var(--fg-muted, #a0a0a0);
  font-size: 11px;
}
.companion-footer--disconnected { color: #e5484d; }
.companion-error { margin: 0; padding: 6px 10px; color: #e5484d; font-size: 11px; }
```

Replace the `var(--…, fallback)` token names with the real names from `frontend/src/theme.css` where an equivalent token exists (open `theme.css` and match background, foreground, muted, border, accent); keep the fallbacks either way.

- [ ] **Step 8: Register the route** in `frontend/src/index.tsx`: add `import CompanionPage from "./pages/CompanionPage";` beside the other page imports and add `<Route path="/companion" component={CompanionPage} />` after the `/graph` route.

- [ ] **Step 9: Add bare mode to App.tsx**

Change the first import line to include `Show`:

```tsx
import { createEffect, createSignal, For, onCleanup, Show, type JSX } from "solid-js";
```

After `const location = useLocation();` add:

```tsx
  const bare = () => location.pathname === "/companion";
```

Change the shell wrapper and wrap the header and palette:

```tsx
        <div class={bare() ? "app-shell app-shell--bare" : "app-shell"}>
          <Show when={!bare()}>
          <header class="app-utility-bar" aria-label="Black Box utility bar">
          ... (existing header unchanged) ...
          </header>
          </Show>
          <main class="app-main">{props.children}</main>
          <Show when={!bare()}>
            <CommandPalette open={paletteOpen()} onClose={() => setPaletteOpen(false)} />
          </Show>
        </div>
```

- [ ] **Step 10: Run the companion tests, then the whole unit suite and type check**

Run: `cd frontend && npx vitest run src/components/companion src/pages/CompanionPage.test.tsx && npm test && npx tsc --noEmit`
Expected: PASS, zero type errors.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/components/companion frontend/src/pages/CompanionPage.tsx frontend/src/pages/CompanionPage.test.tsx frontend/src/companion.css frontend/src/index.tsx frontend/src/App.tsx
git commit -m "Add The Companion Route With Mini, Compact And Expanded Views"
```

---

### Task 7: Backend forwards `/companion`

**Files:**
- Modify: `src/main/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/SpaForwardingController.java:17-27`
- Test: `src/test/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/SpaForwardingTest.java`

- [ ] **Step 1: Add the failing test** to `SpaForwardingTest`:

```java
    @Test
    void companionRouteForwardsToIndexWithoutShadowingTheApi() throws Exception {
        mvc.perform(get("/companion")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/api/companion")).andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test -Dtest=SpaForwardingTest`
Expected: FAIL on `/companion` (404).

- [ ] **Step 3: Add the route** to the `@GetMapping` value array in `SpaForwardingController`, after `"/board"`:

```java
                "/board",
                "/companion"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=SpaForwardingTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/SpaForwardingController.java src/test/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/SpaForwardingTest.java
git commit -m "Forward The Companion Route To The SPA"
```

---

### Task 8: Playwright e2e for the companion

**Files:**
- Create: `frontend/tests/e2e/companion.spec.ts`

**Interfaces:**
- Consumes: the seeded e2e project at `/tmp/black-box-e2e` (`frontend/src/e2e/seedData.ts`, decision headline "Use SolidJS + Vite for the UI rewrite"), the isolated jar on `http://127.0.0.1:8799`.

- [ ] **Step 1: Write the e2e test**

```ts
// frontend/tests/e2e/companion.spec.ts
import { test, expect } from "@playwright/test";

const SHOT_DIR = "test-results/shots";
const E2E_PROJECT_CWD = "/tmp/black-box-e2e";

test("companion discloses mini, projects and items with Black Box links, and receives live handoffs", async ({ page, request }) => {
  await page.goto("/companion");
  await expect(page.locator(".app-utility-bar")).toHaveCount(0);
  const chip = page.getByRole("button", { name: /Black Box companion/ });
  await expect(chip).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/companion-mini.png` });

  await chip.click();
  const projectRow = page.getByRole("button", { name: /^black-box-e2e:/ });
  await expect(projectRow).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/companion-compact.png` });

  await projectRow.click();
  const decision = page.locator(".companion-item").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" });
  await expect(decision).toBeVisible();
  await expect(decision).toHaveAttribute("href", /\/\?view=browse&session=[^&]+&event=[^&]+/);
  await expect(decision).toHaveAttribute("target", "_blank");
  await page.screenshot({ path: `${SHOT_DIR}/companion-expanded.png` });

  const marker = "COMPANION-HANDOFF-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: marker,
      eventType: "Handoff",
      role: "assistant",
      text: `Handoff to next-session: ${marker}`,
      cwd: E2E_PROJECT_CWD,
      metadata: { title: marker, kind: "handoff", contextSummary: marker, nextAction: "Verify the companion shows this", openLoops: ["none"] },
    },
  });
  expect(res.ok()).toBeTruthy();
  await expect(page.locator(".companion-item").filter({ hasText: marker })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText("Next: Verify the companion shows this")).toBeVisible();

  await page.getByRole("button", { name: "River" }).click();
  await expect(page.locator(".companion-item").filter({ hasText: marker })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("button", { name: /^black-box-e2e:/ })).toBeVisible();
});
```

- [ ] **Step 2: Run the e2e suite**

Run: `cd frontend && npm run e2e -- tests/e2e/companion.spec.ts`
Expected: PASS; screenshots under `frontend/test-results/shots/companion-*.png`. Look at `companion-expanded.png` and confirm the row, badge, and next-action line render as intended.

- [ ] **Step 3: Restart the live service** (the e2e run repackaged the jar):

```bash
launchctl kickstart -k gui/$UID/com.nathan.sba-agentic && sleep 3 && curl -fsS http://localhost:8766/api/status | head -c 200
```

- [ ] **Step 4: Commit**

```bash
git add frontend/tests/e2e/companion.spec.ts
git commit -m "Cover The Companion Route End To End"
```

---

### Task 9: Build and commit static assets

**Files:**
- Modify: `src/main/resources/static/**` (generated by `npm run build`)

- [ ] **Step 1: Build**

Run: `cd frontend && npm run build`
Expected: `tsc` clean, Vite writes into `../src/main/resources/static`.

- [ ] **Step 2: Smoke the built route on the isolated port** (never 8766): `mvn -q -DskipTests package && SBA_PORT=8798 SBA_DATASOURCE_URL=jdbc:sqlite:/tmp/companion-smoke.db SBA_ELASTICSEARCH_ENABLED=false SBA_LOCAL_AI_ENABLED=false java -jar target/sba-agentic-0.2.0.jar &` then `curl -fsS http://127.0.0.1:8798/companion | grep -c '<div id="root"'` should print `1`; stop the background jar; then `launchctl kickstart -k gui/$UID/com.nathan.sba-agentic`.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static
git commit -m "Rebuild Static Assets With The Companion Route"
```

---

### Task 10: macOS shell (Swift Package)

**Files:**
- Create: `companion/macos/Package.swift`
- Create: `companion/macos/.gitignore` (content: `.build/`)
- Create: `companion/macos/Sources/BlackBoxCompanion/main.swift`
- Create: `companion/macos/Sources/BlackBoxCompanion/Options.swift`
- Create: `companion/macos/Sources/BlackBoxCompanion/StatusTitle.swift`
- Create: `companion/macos/Sources/BlackBoxCompanion/BridgeMessage.swift`
- Create: `companion/macos/Sources/BlackBoxCompanion/PanelGeometry.swift`
- Create: `companion/macos/Sources/BlackBoxCompanion/CompanionPanel.swift`
- Create: `companion/macos/Sources/BlackBoxCompanion/AppDelegate.swift`
- Create: `companion/macos/Sources/BlackBoxCompanion/SelfTest.swift`
- Test: `companion/macos/Tests/BlackBoxCompanionTests/OptionsTests.swift`
- Test: `companion/macos/Tests/BlackBoxCompanionTests/StatusTitleTests.swift`
- Test: `companion/macos/Tests/BlackBoxCompanionTests/BridgeMessageTests.swift`
- Test: `companion/macos/Tests/BlackBoxCompanionTests/PanelGeometryTests.swift`

**Interfaces:**
- Consumes: the `/companion` route and its bridge messages `{type:"state", pulse, unseen}` and `{type:"mode", mode, width, height}`.
- Produces: `swift run BlackBoxCompanion [--url URL] [--self-test PNG]`, env `BLACKBOX_COMPANION_URL`.

- [ ] **Step 1: Write Package.swift and the failing tests**

```swift
// companion/macos/Package.swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BlackBoxCompanion",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "BlackBoxCompanion", path: "Sources/BlackBoxCompanion"),
        .testTarget(name: "BlackBoxCompanionTests", dependencies: ["BlackBoxCompanion"], path: "Tests/BlackBoxCompanionTests"),
    ]
)
```

```swift
// companion/macos/Tests/BlackBoxCompanionTests/OptionsTests.swift
import XCTest
@testable import BlackBoxCompanion

final class OptionsTests: XCTestCase {
    func testDefaultsToLocalCompanionRoute() {
        let options = Options.parse([], env: [:])
        XCTAssertEqual(options.url.absoluteString, "http://127.0.0.1:8766/companion?embedded=1")
        XCTAssertNil(options.selfTestOutput)
    }

    func testEnvironmentOverridesDefaultAndFlagOverridesEnvironment() {
        let fromEnv = Options.parse([], env: ["BLACKBOX_COMPANION_URL": "http://127.0.0.1:8799/companion"])
        XCTAssertEqual(fromEnv.url.absoluteString, "http://127.0.0.1:8799/companion")
        let fromFlag = Options.parse(["--url", "http://localhost:9/x"], env: ["BLACKBOX_COMPANION_URL": "http://127.0.0.1:8799/companion"])
        XCTAssertEqual(fromFlag.url.absoluteString, "http://localhost:9/x")
    }

    func testSelfTestFlagCapturesOutputPath() {
        let options = Options.parse(["--self-test", "/tmp/shot.png"], env: [:])
        XCTAssertEqual(options.selfTestOutput, "/tmp/shot.png")
    }

    func testInvalidUrlFallsBackToDefault() {
        let options = Options.parse(["--url", "not a url"], env: [:])
        XCTAssertEqual(options.url.absoluteString, "http://127.0.0.1:8766/companion?embedded=1")
    }
}
```

```swift
// companion/macos/Tests/BlackBoxCompanionTests/StatusTitleTests.swift
import XCTest
@testable import BlackBoxCompanion

final class StatusTitleTests: XCTestCase {
    func testGlyphPerPulse() {
        XCTAssertEqual(StatusTitle.render(pulse: .live, unseen: 0), "●")
        XCTAssertEqual(StatusTitle.render(pulse: .idle, unseen: 0), "○")
        XCTAssertEqual(StatusTitle.render(pulse: .connecting, unseen: 0), "◌")
        XCTAssertEqual(StatusTitle.render(pulse: .disconnected, unseen: 0), "◌")
    }

    func testAppendsUnseenCountWhenPositive() {
        XCTAssertEqual(StatusTitle.render(pulse: .live, unseen: 3), "● 3")
        XCTAssertEqual(StatusTitle.render(pulse: .idle, unseen: -1), "○")
    }
}
```

```swift
// companion/macos/Tests/BlackBoxCompanionTests/BridgeMessageTests.swift
import XCTest
@testable import BlackBoxCompanion

final class BridgeMessageTests: XCTestCase {
    func testParsesStateMessage() {
        let body: [String: Any] = ["type": "state", "pulse": "live", "unseen": 2]
        XCTAssertEqual(BridgeMessage.parse(body), .state(pulse: .live, unseen: 2))
    }

    func testParsesModeMessage() {
        let body: [String: Any] = ["type": "mode", "mode": "compact", "width": 340, "height": 420.0]
        XCTAssertEqual(BridgeMessage.parse(body), .mode(name: "compact", width: 340, height: 420))
    }

    func testRejectsGarbage() {
        XCTAssertNil(BridgeMessage.parse("nope"))
        XCTAssertNil(BridgeMessage.parse(["type": "state", "pulse": "weird", "unseen": 1]))
        XCTAssertNil(BridgeMessage.parse(["type": "mode", "mode": "compact"]))
    }
}
```

```swift
// companion/macos/Tests/BlackBoxCompanionTests/PanelGeometryTests.swift
import XCTest
@testable import BlackBoxCompanion

final class PanelGeometryTests: XCTestCase {
    let screen = CGRect(x: 0, y: 0, width: 1440, height: 900)

    func testResizeKeepsTopRightCornerAnchored() {
        let current = CGRect(x: 1000, y: 500, width: 132, height: 36)
        let next = PanelGeometry.frame(resizing: current, to: CGSize(width: 340, height: 420), within: screen)
        XCTAssertEqual(next, CGRect(x: 792, y: 116, width: 340, height: 420))
    }

    func testClampsSizeAndPosition() {
        let current = CGRect(x: 10, y: 10, width: 132, height: 36)
        let tiny = PanelGeometry.frame(resizing: current, to: CGSize(width: -5, height: 0), within: screen)
        XCTAssertEqual(tiny.size, CGSize(width: 100, height: 28))
        let huge = PanelGeometry.frame(resizing: current, to: CGSize(width: 9999, height: 9999), within: screen)
        XCTAssertEqual(huge.size, CGSize(width: 1440, height: 900))
        XCTAssertEqual(huge.origin, CGPoint(x: 0, y: 0))
        let offscreen = PanelGeometry.frame(resizing: CGRect(x: 1400, y: 5, width: 132, height: 36), to: CGSize(width: 400, height: 560), within: screen)
        XCTAssertGreaterThanOrEqual(offscreen.minX, screen.minX)
        XCTAssertGreaterThanOrEqual(offscreen.minY, screen.minY)
        XCTAssertLessThanOrEqual(offscreen.maxX, screen.maxX)
        XCTAssertLessThanOrEqual(offscreen.maxY, screen.maxY)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd companion/macos && swift test`
Expected: build failure (missing sources).

- [ ] **Step 3: Implement the pure pieces**

```swift
// companion/macos/Sources/BlackBoxCompanion/Options.swift
import Foundation

struct Options {
    static let defaultURL = URL(string: "http://127.0.0.1:8766/companion?embedded=1")!

    var url: URL
    var selfTestOutput: String?

    static func parse(_ args: [String], env: [String: String]) -> Options {
        var options = Options(url: defaultURL, selfTestOutput: nil)
        if let fromEnv = env["BLACKBOX_COMPANION_URL"], let url = validURL(fromEnv) {
            options.url = url
        }
        var index = 0
        while index < args.count {
            let arg = args[index]
            let value: String? = index + 1 < args.count ? args[index + 1] : nil
            switch arg {
            case "--url":
                if let value, let url = validURL(value) { options.url = url }
                index += 2
            case "--self-test":
                options.selfTestOutput = value
                index += 2
            default:
                index += 1
            }
        }
        return options
    }

    private static func validURL(_ text: String) -> URL? {
        guard let url = URL(string: text), let scheme = url.scheme, scheme == "http" || scheme == "https", url.host != nil else { return nil }
        return url
    }
}
```

```swift
// companion/macos/Sources/BlackBoxCompanion/StatusTitle.swift
enum Pulse: String {
    case connecting, live, idle, disconnected
}

enum StatusTitle {
    static func render(pulse: Pulse, unseen: Int) -> String {
        let glyph: String
        switch pulse {
        case .live: glyph = "●"
        case .idle: glyph = "○"
        case .connecting, .disconnected: glyph = "◌"
        }
        return unseen > 0 ? "\(glyph) \(unseen)" : glyph
    }
}
```

```swift
// companion/macos/Sources/BlackBoxCompanion/BridgeMessage.swift
import Foundation

enum BridgeMessage: Equatable {
    case state(pulse: Pulse, unseen: Int)
    case mode(name: String, width: Double, height: Double)

    static func parse(_ body: Any) -> BridgeMessage? {
        guard let dict = body as? [String: Any], let type = dict["type"] as? String else { return nil }
        switch type {
        case "state":
            guard let pulseName = dict["pulse"] as? String, let pulse = Pulse(rawValue: pulseName) else { return nil }
            let unseen = (dict["unseen"] as? NSNumber)?.intValue ?? 0
            return .state(pulse: pulse, unseen: unseen)
        case "mode":
            guard let name = dict["mode"] as? String,
                  let width = (dict["width"] as? NSNumber)?.doubleValue,
                  let height = (dict["height"] as? NSNumber)?.doubleValue else { return nil }
            return .mode(name: name, width: width, height: height)
        default:
            return nil
        }
    }
}
```

```swift
// companion/macos/Sources/BlackBoxCompanion/PanelGeometry.swift
import CoreGraphics

enum PanelGeometry {
    static let minimumSize = CGSize(width: 100, height: 28)

    /// Resizes `current` to `size`, keeping its top-right corner fixed, clamped to `screen`.
    static func frame(resizing current: CGRect, to size: CGSize, within screen: CGRect) -> CGRect {
        let width = min(max(size.width, minimumSize.width), screen.width)
        let height = min(max(size.height, minimumSize.height), screen.height)
        var origin = CGPoint(x: current.maxX - width, y: current.maxY - height)
        origin.x = min(max(origin.x, screen.minX), screen.maxX - width)
        origin.y = min(max(origin.y, screen.minY), screen.maxY - height)
        return CGRect(origin: origin, size: CGSize(width: width, height: height))
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd companion/macos && swift test`
Expected: the four pure-piece test files pass (the app files below are not yet needed for these tests, but `main.swift` must exist for the executable target to build; create it in Step 5 before running if the build complains).

- [ ] **Step 5: Implement the AppKit pieces**

```swift
// companion/macos/Sources/BlackBoxCompanion/CompanionPanel.swift
import AppKit

final class CompanionPanel: NSPanel {
    init(contentSize: NSSize) {
        super.init(
            contentRect: NSRect(origin: .zero, size: contentSize),
            styleMask: [.borderless, .nonactivatingPanel, .resizable],
            backing: .buffered,
            defer: false
        )
        level = .floating
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        isMovableByWindowBackground = true
        hidesOnDeactivate = false
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        _ = setFrameAutosaveName("BlackBoxCompanionPanel")
        if frame.origin == .zero, let screen = NSScreen.main?.visibleFrame {
            setFrameOrigin(NSPoint(x: screen.maxX - contentSize.width - 16, y: screen.maxY - contentSize.height - 16))
        }
    }

    override var canBecomeKey: Bool { true }
}
```

```swift
// companion/macos/Sources/BlackBoxCompanion/AppDelegate.swift
import AppKit
import WebKit

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKUIDelegate {
    private let options: Options
    private var statusItem: NSStatusItem!
    private var panel: CompanionPanel!
    private var webView: WKWebView!
    private var toggleItem: NSMenuItem!

    init(options: Options) {
        self.options = options
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(self, name: "companion")
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.uiDelegate = self
        webView.setValue(false, forKey: "drawsBackground")

        panel = CompanionPanel(contentSize: NSSize(width: 132, height: 36))
        panel.contentView = webView

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.title = StatusTitle.render(pulse: .connecting, unseen: 0)
        let menu = NSMenu()
        toggleItem = NSMenuItem(title: "Hide Companion", action: #selector(togglePanel), keyEquivalent: "")
        toggleItem.target = self
        menu.addItem(toggleItem)
        let open = NSMenuItem(title: "Open Black Box", action: #selector(openBlackBox), keyEquivalent: "")
        open.target = self
        menu.addItem(open)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        statusItem.menu = menu

        webView.load(URLRequest(url: options.url))
        panel.orderFrontRegardless()
    }

    @objc private func togglePanel() {
        if panel.isVisible {
            panel.orderOut(nil)
            toggleItem.title = "Show Companion"
        } else {
            panel.orderFrontRegardless()
            toggleItem.title = "Hide Companion"
        }
    }

    @objc private func openBlackBox() {
        guard var components = URLComponents(url: options.url, resolvingAgainstBaseURL: false) else { return }
        components.path = "/"
        components.query = nil
        if let url = components.url { NSWorkspace.shared.open(url) }
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let parsed = BridgeMessage.parse(message.body) else { return }
        switch parsed {
        case let .state(pulse, unseen):
            statusItem.button?.title = StatusTitle.render(pulse: pulse, unseen: unseen)
        case let .mode(_, width, height):
            let screen = panel.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? panel.frame
            let frame = PanelGeometry.frame(resizing: panel.frame, to: CGSize(width: width, height: height), within: screen)
            panel.setFrame(frame, display: true, animate: true)
        }
    }

    // target="_blank" links open in the default browser instead of inside the panel.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { NSWorkspace.shared.open(url) }
        return nil
    }
}
```

```swift
// companion/macos/Sources/BlackBoxCompanion/SelfTest.swift
import AppKit
import WebKit

/// Loads the companion route in a real panel, waits for `.companion`, writes a PNG, exits 0.
final class SelfTest: NSObject, WKNavigationDelegate {
    private let url: URL
    private let output: String
    private var panel: CompanionPanel!
    private var webView: WKWebView!

    static func run(url: URL, output: String) -> Never {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        let test = SelfTest(url: url, output: output)
        test.start()
        app.run()
        exit(2)
    }

    private init(url: URL, output: String) {
        self.url = url
        self.output = output
    }

    private func start() {
        panel = CompanionPanel(contentSize: NSSize(width: 340, height: 420))
        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 340, height: 420))
        webView.navigationDelegate = self
        panel.contentView = webView
        panel.orderFrontRegardless()
        webView.load(URLRequest(url: url))
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { SelfTest.fail("timeout after 20s") }
    }

    private static func fail(_ reason: String) -> Never {
        FileHandle.standardError.write("self-test: \(reason)\n".data(using: .utf8)!)
        exit(1)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [self] in
            webView.evaluateJavaScript("(document.querySelector('.companion') || {}).getAttribute ? document.querySelector('.companion').getAttribute('data-mode') : null") { result, _ in
                guard let mode = result as? String else { SelfTest.fail(".companion not rendered") }
                webView.takeSnapshot(with: nil) { image, error in
                    guard let image, let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
                          let png = rep.representation(using: .png, properties: [:]) else {
                        SelfTest.fail("snapshot failed: \(String(describing: error))")
                    }
                    do {
                        try png.write(to: URL(fileURLWithPath: self.output))
                        print("self-test: ok mode=\(mode) png=\(self.output)")
                        exit(0)
                    } catch {
                        SelfTest.fail("write failed: \(error)")
                    }
                }
            }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        SelfTest.fail("navigation failed: \(error)")
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        SelfTest.fail("provisional navigation failed: \(error)")
    }
}
```

```swift
// companion/macos/Sources/BlackBoxCompanion/main.swift
import AppKit

let options = Options.parse(Array(CommandLine.arguments.dropFirst()), env: ProcessInfo.processInfo.environment)
if let output = options.selfTestOutput {
    SelfTest.run(url: options.url, output: output)
}
let app = NSApplication.shared
let delegate = AppDelegate(options: options)
app.delegate = delegate
app.run()
```

- [ ] **Step 6: Build and run the unit tests**

Run: `cd companion/macos && swift build && swift test`
Expected: build succeeds, 11 tests pass.

- [ ] **Step 7: Exercise the real load path against the isolated jar**

Start the isolated jar (never 8766): `mvn -q -DskipTests package && SBA_PORT=8798 SBA_DATASOURCE_URL=jdbc:sqlite:/tmp/companion-smoke.db SBA_ELASTICSEARCH_ENABLED=false SBA_LOCAL_AI_ENABLED=false java -jar target/sba-agentic-0.2.0.jar &`, wait for `curl -fsS http://127.0.0.1:8798/api/status`, then:

```bash
cd companion/macos && swift run BlackBoxCompanion --self-test /tmp/companion-shell.png --url http://127.0.0.1:8798/companion?embedded=1
```

Expected: prints `self-test: ok mode=mini png=/tmp/companion-shell.png` and exits 0. Open the PNG and confirm the chip renders. Stop the background jar, then `launchctl kickstart -k gui/$UID/com.nathan.sba-agentic`.

- [ ] **Step 8: Manual check against the live service** (read-only, Nathan's real data): `swift run BlackBoxCompanion` for 30 seconds. Confirm the menubar glyph appears, the floating chip shows a count, clicking it expands to the project list, clicking a project resizes the panel, and a row opens the browser at a `?view=browse&session=…&event=…` URL. Quit from the menubar. Record what you saw in the final handoff.

- [ ] **Step 9: Commit**

```bash
git add companion/macos
git commit -m "Add The macOS Companion Shell"
```

---

### Task 11: Docs, guide line, and handoff

**Files:**
- Create: `docs/companion.md`
- Modify: `CLAUDE.md` (Commands section and the wire-surfaces paragraph)

- [ ] **Step 1: Write docs/companion.md**

````markdown
# Ambient Companion

A chrome-less Black Box route plus a thin macOS shell that keeps agent activity in the corner of
the screen without becoming a notification feed.

## Route

`http://127.0.0.1:8766/companion` renders three levels:

- **Mini**: a chip with the pulse state (`connecting`, `live`, `idle`, `offline`) and the count of
  meaningful items you have not opened.
- **Compact**: active projects (a live session in the last 10 minutes, or a Decision, Handoff, or
  Observation in the last 24 hours), sorted by unseen count then recency. `River` shows every item
  across projects in time order.
- **Expanded**: one project's items, or the river, each linking to the exact event in the browse
  view. Handoff rows show the next action and open-loop count. A handoff is a baton, not a
  completion.

Escape steps down one level. Mode and seen-state persist in the browser's `localStorage`.

Only `Decision`, `Handoff`, and `Observation` events become rows. Tool-call activity only drives
the pulse. The route reads the existing stream and `/api/events` query; it adds no endpoints and
never writes to Black Box.

## macOS shell

`companion/macos` is a Swift Package. It hosts the route in a floating, non-activating panel and
mirrors pulse plus unseen count into a menubar item.

```bash
cd companion/macos
swift build
swift run BlackBoxCompanion                      # default: http://127.0.0.1:8766/companion?embedded=1
swift run BlackBoxCompanion --url http://127.0.0.1:8799/companion?embedded=1
swift run BlackBoxCompanion --self-test /tmp/companion.png   # loads the page, writes a PNG, exits 0
swift test
```

The menubar menu offers Show/Hide Companion, Open Black Box, and Quit. The panel resizes as the
page changes level and remembers its position. Links open in the default browser.

Design: `docs/superpowers/specs/2026-09-24-ambient-companion-design.md`.
````

- [ ] **Step 2: Update CLAUDE.md**: in the Commands section add a `macOS companion shell (Swift, in companion/macos/)` block with `swift build`, `swift test`, and `swift run BlackBoxCompanion --self-test /tmp/companion.png`; in the wire-surfaces paragraph append one sentence: "The `/companion` route (see `docs/companion.md`) is a chrome-less ambient view over the same stream and query surfaces."

- [ ] **Step 3: Run the full verification once more**

Run: `cd frontend && npm test && npx tsc --noEmit && cd .. && mvn -q test -Dtest=SpaForwardingTest && cd companion/macos && swift test`
Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add docs/companion.md CLAUDE.md
git commit -m "Document The Ambient Companion"
```

- [ ] **Step 5: Capture the Black Box handoff** (MCP `captureHandoff`, repo `/Users/nathan/Developer/proj/sba-agentic`): branch `companion-first-slice`, worktree path, commit list, verification run (vitest count, e2e result and screenshot paths, swift test count, self-test PNG path, manual live check observations), the Linear issue identifier from Task 0, what was not done (not pushed, no PR), and the next action: "Nathan reviews the branch and decides whether to push and open a PR."

- [ ] **Step 6: Report** in the session: branch state (`git log --oneline 2026-09-24-iterate..companion-first-slice`), verification summary, and the exact manual steps for Nathan: `cd companion/macos && swift run BlackBoxCompanion`.
