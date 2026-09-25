import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import type { CompanionModel, MeaningfulItem } from "../../lib/companion/model";
import CompactList from "./CompactList";

const item: MeaningfulItem = {
  id: "d1",
  kind: "decision",
  eventType: "Decision",
  projectKey: "keyA",
  projectName: "a",
  sessionId: "s1",
  source: "claude",
  headline: "Pick A",
  nextAction: null,
  openLoops: [],
  observedAt: new Date(Date.now() - 60_000).toISOString(),
  href: "/?view=browse&session=s1&event=d1",
  seen: false,
};

const model: CompanionModel = {
  pulse: "live",
  lastEventAt: new Date(Date.now() - 5_000).toISOString(),
  unseenTotal: 1,
  projects: [
    {
      key: "keyA",
      name: "a",
      liveSessions: 2,
      lastActivityAt: item.observedAt,
      lastCaptureAt: item.observedAt,
      unseen: 1,
      latest: item,
      items: [item],
    },
  ],
  river: [item],
};

describe("CompactList", () => {
  it("lists projects with live count, unseen badge and latest headline", () => {
    const onOpenProject = vi.fn();
    render(() => (
      <CompactList
        model={model}
        onOpenProject={onOpenProject}
        onOpenRiver={() => {}}
        onCollapse={() => {}}
      />
    ));
    const row = screen.getByRole("button", {
      name: /a: 2 live, 1 unseen, latest Decision: Pick A/,
    });
    expect(row).toHaveTextContent("2 live");
    expect(row).toHaveTextContent("Pick A");
    fireEvent.click(row);
    expect(onOpenProject).toHaveBeenCalledWith("keyA");
    expect(screen.getByText(/Live · last event/)).toBeInTheDocument();
  });

  it("matches the accessible name to the visible 'quiet' text for a project with no live session", () => {
    const quiet = { ...model.projects[0], liveSessions: 0 };
    render(() => (
      <CompactList
        model={{ ...model, projects: [quiet] }}
        onOpenProject={() => {}}
        onOpenRiver={() => {}}
        onCollapse={() => {}}
      />
    ));
    expect(screen.getByRole("button", { name: /a: quiet, 1 unseen/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /0 live/ })).not.toBeInTheDocument();
  });

  it("keeps the row element when a card updates in place", () => {
    const [current, setCurrent] = createSignal(model);
    render(() => (
      <CompactList
        model={current()}
        onOpenProject={() => {}}
        onOpenRiver={() => {}}
        onCollapse={() => {}}
      />
    ));
    const row = screen.getByRole("button", { name: /a: 2 live, 1 unseen/ });
    setCurrent({ ...model, projects: [{ ...model.projects[0], liveSessions: 3 }] });
    expect(screen.getByRole("button", { name: /a: 3 live, 1 unseen/ })).toBe(row);
  });

  it("shows the quiet empty state and the river and collapse controls", () => {
    const onOpenRiver = vi.fn();
    const onCollapse = vi.fn();
    render(() => (
      <CompactList
        model={{ ...model, projects: [], river: [], unseenTotal: 0, pulse: "idle" }}
        onOpenProject={() => {}}
        onOpenRiver={onOpenRiver}
        onCollapse={onCollapse}
      />
    ));
    expect(screen.getByText("Quiet. No active projects in the last 24h.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "River" }));
    fireEvent.click(screen.getByRole("button", { name: "Collapse" }));
    expect(onOpenRiver).toHaveBeenCalledTimes(1);
    expect(onCollapse).toHaveBeenCalledTimes(1);
  });

  it("keeps rows mounted through a refresh that already has projects, instead of flashing Loading…", () => {
    const [loading, setLoading] = createSignal(false);
    render(() => (
      <CompactList
        model={model}
        loading={loading()}
        onOpenProject={() => {}}
        onOpenRiver={() => {}}
        onCollapse={() => {}}
      />
    ));
    const row = screen.getByRole("button", { name: /a: 2 live, 1 unseen/ });
    row.focus();
    // A refresh (reconnect live->down->live, or the 30s error-retry tick) sets loading back to true
    // while the previous model (still with projects) is showing.
    setLoading(true);
    expect(screen.getByRole("button", { name: /a: 2 live, 1 unseen/ })).toBe(row);
    expect(document.activeElement).toBe(row);
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();
  });

  it("shows a neutral loading state instead of the quiet empty state before the first load finishes", () => {
    render(() => (
      <CompactList
        model={{ ...model, projects: [], river: [], unseenTotal: 0, pulse: "connecting" }}
        loading
        onOpenProject={() => {}}
        onOpenRiver={() => {}}
        onCollapse={() => {}}
      />
    ));
    expect(
      screen.queryByText("Quiet. No active projects in the last 24h."),
    ).not.toBeInTheDocument();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });
});
