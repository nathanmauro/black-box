import { fireEvent, render, screen } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { CompanionModel, ExpandedViewState, MeaningfulItem } from "../../lib/companion/model";
import ExpandedView from "./ExpandedView";

const handoff: MeaningfulItem = {
  id: "h1",
  kind: "handoff",
  eventType: "Handoff",
  projectKey: "keyA",
  projectName: "a",
  sessionId: "s1",
  source: "codex",
  headline: "Wired the bridge",
  nextAction: "Run the e2e suite",
  openLoops: ["restart service"],
  observedAt: new Date(Date.now() - 120_000).toISOString(),
  href: "/?view=browse&session=s1&event=h1&project=keyA",
  seen: false,
};
const decision: MeaningfulItem = {
  ...handoff,
  id: "d1",
  kind: "decision",
  eventType: "Decision",
  projectKey: "keyB",
  projectName: "b",
  headline: "Pick B",
  nextAction: null,
  openLoops: [],
  href: "/?view=browse&session=s1&event=d1&project=keyB",
  seen: true,
};

const model: CompanionModel = {
  pulse: "idle",
  lastEventAt: null,
  unseenTotal: 1,
  projects: [
    {
      key: "keyA",
      name: "a",
      liveSessions: 1,
      lastActivityAt: handoff.observedAt,
      lastCaptureAt: handoff.observedAt,
      unseen: 1,
      latest: handoff,
      items: [handoff],
    },
    {
      key: "keyB",
      name: "b",
      liveSessions: 0,
      lastActivityAt: decision.observedAt,
      lastCaptureAt: decision.observedAt,
      unseen: 0,
      latest: decision,
      items: [decision],
    },
  ],
  river: [handoff, decision],
};

describe("ExpandedView", () => {
  it("renders one project's items with links, next action and open loops", () => {
    const onBack = vi.fn();
    render(() => (
      <ExpandedView
        model={model}
        view={{ kind: "project", projectKey: "keyA", projectName: "a" }}
        onBack={onBack}
        onToggleView={() => {}}
        onCollapse={() => {}}
      />
    ));
    expect(screen.getByText("a")).toBeInTheDocument();
    expect(screen.getByText(/1 live/)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /Wired the bridge/ });
    expect(link).toHaveAttribute("href", "/?view=browse&session=s1&event=h1&project=keyA");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveClass("companion-item--unseen");
    expect(link).toHaveTextContent("Unseen"); // conveyed to assistive tech, not just by the border color
    expect(screen.getByText("Next: Run the e2e suite")).toBeInTheDocument();
    expect(screen.getByText("1 open loop")).toBeInTheDocument();
    expect(screen.queryByText("Pick B")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Back to projects" }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it("keeps a project's real name and an active strip once its card has aged out of the model", () => {
    render(() => (
      <ExpandedView
        model={model}
        view={{ kind: "project", projectKey: "keyC", projectName: "gone-project" }}
        onBack={() => {}}
        onToggleView={() => {}}
        onCollapse={() => {}}
      />
    ));
    expect(screen.getByText("gone-project")).toBeInTheDocument();
    expect(screen.queryByText("Project")).not.toBeInTheDocument();
    expect(screen.getByText(/0 live · activity none · capture none/)).toBeInTheDocument();
  });

  it("shows a disconnected footer instead of reading as quiet when the stream is down", () => {
    render(() => (
      <ExpandedView
        model={{ ...model, pulse: "disconnected" }}
        view={{ kind: "river" }}
        onBack={() => {}}
        onToggleView={() => {}}
        onCollapse={() => {}}
      />
    ));
    expect(screen.getByText("Disconnected from Black Box")).toBeInTheDocument();
  });

  it("renders the river across projects with a project prefix and a toggle", () => {
    const onToggleView = vi.fn();
    render(() => (
      <ExpandedView
        model={model}
        view={{ kind: "river" }}
        onBack={() => {}}
        onToggleView={onToggleView}
        onCollapse={() => {}}
      />
    ));
    expect(screen.getByText("River")).toBeInTheDocument();
    const links = screen.getAllByRole("link");
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveTextContent("Unseen"); // handoff, unseen
    expect(links[1]).not.toHaveTextContent("Unseen"); // decision, already seen
    expect(screen.getByText("b")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "By project" }));
    expect(onToggleView).toHaveBeenCalledTimes(1);
  });

  describe("recall form", () => {
    function renderForm(view: ExpandedViewState, recallProject: string | null) {
      const open = vi.spyOn(window, "open").mockImplementation(() => null);
      render(() => (
        <ExpandedView
          model={model}
          view={view}
          recallProject={recallProject}
          onBack={() => {}}
          onToggleView={() => {}}
          onCollapse={() => {}}
        />
      ));
      const field = screen.getByRole("textbox", { name: /^Recall/ });
      const button = screen.getByRole("button", { name: "Recall" });
      return { open, field: field as HTMLInputElement, button: button as HTMLButtonElement };
    }
    const projectView: ExpandedViewState = {
      kind: "project",
      projectKey: "keyA",
      projectName: "a",
    };

    afterEach(() => vi.restoreAllMocks());

    it("is a labelled search form whose submit opens Recall for the project in a new context", () => {
      const { open, field, button } = renderForm(projectView, "/repo/a");
      expect(screen.getByRole("search")).toContainElement(field);
      expect(field).toHaveAccessibleName("Recall in a");
      expect(button).toHaveAttribute("type", "submit");
      expect(field.form).toBe(button.form);
      fireEvent.input(field, { target: { value: "  C++ & #42 café  " } });
      fireEvent.submit(field.form!);
      expect(open).toHaveBeenCalledTimes(1);
      expect(open).toHaveBeenCalledWith(
        "/recall?project=%2Frepo%2Fa&query=C%2B%2B+%26+%2342+caf%C3%A9&run=1",
        "_blank",
        "noopener,noreferrer",
      );
    });

    it("searches all projects from the river", () => {
      const { open, field } = renderForm({ kind: "river" }, null);
      expect(field).toHaveAccessibleName("Recall across projects");
      fireEvent.input(field, { target: { value: "bridge" } });
      fireEvent.submit(field.form!);
      expect(open).toHaveBeenCalledWith(
        "/recall?query=bridge&run=1",
        "_blank",
        "noopener,noreferrer",
      );
    });

    it("does not submit blank or one-character questions", () => {
      const { open, field, button } = renderForm(projectView, "/repo/a");
      expect(button).toBeDisabled();
      for (const value of ["", "   ", " x "]) {
        fireEvent.input(field, { target: { value } });
        expect(button).toBeDisabled();
        const submit = new Event("submit", { bubbles: true, cancelable: true });
        field.form!.dispatchEvent(submit);
        expect(submit.defaultPrevented).toBe(true);
      }
      expect(open).not.toHaveBeenCalled();
      fireEvent.input(field, { target: { value: " xy " } });
      expect(button).toBeEnabled();
    });

    it("handles Escape itself: clears the text, then moves focus to Back", () => {
      const { field } = renderForm(projectView, "/repo/a");
      field.focus();
      fireEvent.input(field, { target: { value: "half a thought" } });
      const first = new KeyboardEvent("keydown", {
        key: "Escape",
        bubbles: true,
        cancelable: true,
      });
      field.dispatchEvent(first);
      expect(first.defaultPrevented).toBe(true);
      expect(field).toHaveValue("");
      expect(field).toHaveFocus();
      const second = new KeyboardEvent("keydown", {
        key: "Escape",
        bubbles: true,
        cancelable: true,
      });
      field.dispatchEvent(second);
      expect(second.defaultPrevented).toBe(true);
      expect(screen.getByRole("button", { name: "Back to projects" })).toHaveFocus();
    });
  });
});
