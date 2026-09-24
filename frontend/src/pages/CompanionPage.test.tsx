import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { createSignal, type JSX } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import App from "../App";
import { LiveStoreContext, type LiveStatus, type LiveStore } from "../lib/sse";
import CompanionPage from "./CompanionPage";

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; class?: string; children: JSX.Element }) => (
    <a href={props.href} class={props.class}>
      {props.children}
    </a>
  ),
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: "/companion" }),
  useSearchParams: () => [{}],
}));

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  const now = Date.now();
  return {
    ...actual,
    getProjects: vi.fn(async () => [{ projectKey: "keyA", canonicalKey: "/repo/a", label: "/repo/a", sessionCount: 1, eventCount: 1, savedMeldCount: 0, firstSeenAt: new Date(now - 86_400_000).toISOString(), lastSeenAt: new Date(now).toISOString(), scopes: [{ projectKey: "keyA", canonicalKey: "/repo/a", label: "/repo/a", primary: true }] }]),
    getSessions: vi.fn(async () => [{ id: "s1", source: "claude", clientSessionId: "c1", title: "t", cwd: "/repo/a", startedAt: new Date(now - 60_000).toISOString(), lastSeenAt: new Date(now - 300_000).toISOString(), eventCount: 3 }]),
    getEventFeed: vi.fn(async () => ({ limit: 200, count: 1, items: [{ id: "d1", sessionId: "s1", source: "claude", clientSessionId: "c1", eventType: "Decision", text: "Pick A", cwd: "/repo/a", observedAt: new Date(now - 30_000).toISOString() }] })),
    getEvent: vi.fn(),
    getCodeProjectScopes: vi.fn(async () => []),
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
    const row = await screen.findByRole("button", { name: /^a: 1 live, 1 unseen/ });
    fireEvent.click(row);
    expect(await screen.findByRole("link", { name: /Pick A/ })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(await screen.findByRole("button", { name: /^a: 1 live, 0 unseen/ })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(await screen.findByRole("button", { name: "Black Box companion: idle, 0 unseen" })).toBeInTheDocument();
  });

  it("renders chrome-less inside the app shell on /companion", async () => {
    window.localStorage.clear();
    render(() => (
      <App>
        <CompanionPage />
      </App>
    ));
    expect(await screen.findByRole("button", { name: /Black Box companion/ })).toBeInTheDocument();
    expect(screen.queryByRole("banner", { name: "Black Box utility bar" })).not.toBeInTheDocument();
    expect(document.querySelector(".companion")).not.toHaveClass("companion--embedded");
    fireEvent.keyDown(window, { key: "k", metaKey: true });
    expect(screen.queryByRole("dialog", { name: "Command palette" })).not.toBeInTheDocument();
  });

  it("marks the page embedded when the shell loads it with ?embedded=1", async () => {
    window.localStorage.clear();
    window.history.replaceState(null, "", "/companion?embedded=1");
    try {
      render(() => (
        <LiveStoreContext.Provider value={fakeLive()}>
          <CompanionPage />
        </LiveStoreContext.Provider>
      ));
      await screen.findByRole("button", { name: /Black Box companion/ });
      expect(document.querySelector(".companion")).toHaveClass("companion--embedded");
    } finally {
      window.history.replaceState(null, "", "/");
    }
  });
});
