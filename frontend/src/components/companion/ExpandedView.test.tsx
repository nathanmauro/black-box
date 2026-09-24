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
