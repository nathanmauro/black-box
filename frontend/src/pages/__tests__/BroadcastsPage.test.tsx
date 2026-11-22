import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getBroadcasts, type ProjectBroadcast } from "../../lib/api";
import { LiveStoreContext, type BroadcastChanged, type LiveStore } from "../../lib/sse";
import BroadcastsPage from "../BroadcastsPage";

type Params = { kind?: string; stale?: string; cleared?: string };

let params: Params;
let updateParams: SetStoreFunction<Params>;
const setParams = vi.fn();

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; children: JSX.Element }) => <a href={props.href}>{props.children}</a>,
  useSearchParams: () => [params, setParams],
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();

  return { ...actual, getBroadcasts: vi.fn() };
});

function broadcast(overrides: Partial<ProjectBroadcast>): ProjectBroadcast {
  return {
    id: "b-1",
    projectScope: "/work/black-box",
    key: "release-lock",
    kind: "hazard",
    message: "Release build running; do not package over the live jar.",
    severity: "warn",
    createdAt: "2026-11-22T10:00:00Z",
    updatedAt: "2026-11-22T10:00:00Z",
    status: "active",
    stale: false,
    ...overrides,
  };
}

const HAZARD = broadcast({});
const TENTACLE = broadcast({
  id: "b-2",
  projectScope: "/work/cockpit",
  key: "worktree:broadcasts-ui",
  kind: "tentacle",
  severity: "info",
  message: "Broadcasts UI branch is unpushed.",
  followUpAt: "2026-11-20T00:00:00Z",
  stale: true,
});

function liveStore() {
  const listeners = new Set<(event: BroadcastChanged) => void>();
  const store: LiveStore = {
    status: () => "live",
    events: () => [],
    onEventAppended: () => () => undefined,
    onSessionUpdated: () => () => undefined,
    onBroadcastChanged: (callback) => {
      listeners.add(callback);

      return () => listeners.delete(callback);
    },
  };
  const emit = (event: BroadcastChanged) => listeners.forEach((listener) => listener(event));

  return { store, emit };
}

function renderPage(store: LiveStore) {
  return render(() => (
    <LiveStoreContext.Provider value={store}>
      <BroadcastsPage />
    </LiveStoreContext.Provider>
  ));
}

beforeEach(() => {
  [params, updateParams] = createStore<Params>({});
  setParams.mockReset();
  setParams.mockImplementation((next: Params) => updateParams(next));
  vi.mocked(getBroadcasts).mockReset();
  vi.mocked(getBroadcasts).mockResolvedValue({
    broadcasts: [HAZARD, TENTACLE],
    count: 2,
    staleAfterHours: 72,
    asOf: "2026-11-22T12:00:00Z",
  });
});

describe("BroadcastsPage", () => {
  it("groups every project's live broadcasts and flags stale tentacles", async () => {
    renderPage(liveStore().store);

    const blackBox = await screen.findByRole("region", { name: "/work/black-box" });

    expect(within(blackBox).getByText(HAZARD.message)).toBeTruthy();
    expect(within(blackBox).getByText("warn")).toBeTruthy();

    const cockpit = screen.getByRole("region", { name: "/work/cockpit" });

    expect(within(cockpit).getByText("stale")).toBeTruthy();
    expect(within(cockpit).getByText(/follow-up due/)).toBeTruthy();
    expect(getBroadcasts).toHaveBeenCalledWith(
      expect.objectContaining({ staleOnly: false, includeCleared: false }),
    );
  });

  it("passes kind and stale filters through to the list endpoint", async () => {
    renderPage(liveStore().store);
    await screen.findByText(HAZARD.message);

    fireEvent.click(screen.getByRole("button", { name: "tentacle" }));
    fireEvent.click(screen.getByRole("button", { name: "stale tentacles only" }));

    await waitFor(() =>
      expect(getBroadcasts).toHaveBeenLastCalledWith(
        expect.objectContaining({ kind: "tentacle", staleOnly: true }),
      ),
    );
  });

  it("reloads when the stream reports a broadcast change", async () => {
    const live = liveStore();

    renderPage(live.store);
    await screen.findByText(HAZARD.message);

    vi.mocked(getBroadcasts).mockResolvedValue({
      broadcasts: [TENTACLE],
      count: 1,
      staleAfterHours: 72,
      asOf: "2026-11-22T12:01:00Z",
    });
    live.emit({
      projectScope: HAZARD.projectScope,
      key: HAZARD.key,
      kind: "hazard",
      change: "cleared",
    });

    await waitFor(() => expect(screen.queryByText(HAZARD.message)).toBeNull());
    expect(getBroadcasts).toHaveBeenCalledTimes(2);
  });
});
