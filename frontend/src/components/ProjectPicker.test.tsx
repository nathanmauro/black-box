import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { ProjectSummary } from "../lib/api";
import ProjectPicker from "./ProjectPicker";

const projects: ProjectSummary[] = [
  {
    projectKey: "sba-key",
    canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
    label: "~/Developer/proj/sba-agentic",
    sessionCount: 4,
    eventCount: 120,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T20:00:00Z",
    scopes: [
      {
        projectKey: "sba-key",
        canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
        label: "~/Developer/proj/sba-agentic",
        primary: true,
      },
      {
        projectKey: "sba-worktree-key",
        canonicalKey: "/Users/nathan/.codex/worktrees/abc/sba-agentic",
        label: "SBA worktree",
        primary: false,
      },
    ],
  },
  {
    projectKey: "cockpit-key",
    canonicalKey: "/Users/nathan/Developer/proj/cockpit",
    label: "~/Developer/proj/cockpit",
    sessionCount: 2,
    eventCount: 40,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T21:00:00Z",
  },
];

describe("ProjectPicker", () => {
  it("selects all projects and fuzzy project matches", async () => {
    const onSelect = vi.fn();
    render(() => <ProjectPicker projects={projects} selectedProjectKey={undefined} onSelect={onSelect} />);

    const trigger = screen.getByRole("button", { name: /All projects/ });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    await waitFor(() => expect(screen.getByLabelText("Search projects")).toHaveFocus());
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "sba" } });

    const listbox = screen.getByRole("listbox", { name: "Project results" });
    expect(within(listbox).getByText("sba-agentic")).toBeInTheDocument();
    expect(within(listbox).getByText("/Users/nathan/Developer/proj/sba-agentic")).toBeInTheDocument();
    expect(within(listbox).getByText(/2 scopes/)).toBeInTheDocument();

    fireEvent.click(within(listbox).getByRole("option", { name: /sba-agentic/ }));
    expect(onSelect).toHaveBeenCalledWith("sba-key");

    fireEvent.click(screen.getByRole("button", { name: /sba-agentic/ }));
    fireEvent.click(screen.getByRole("button", { name: "All projects" }));
    expect(onSelect).toHaveBeenLastCalledWith(undefined);
  });

  it("searches variant scopes and resolves a selected alias to its grouped project", async () => {
    const onSelect = vi.fn();
    render(() => <ProjectPicker projects={projects} selectedProjectKey="sba-worktree-key" onSelect={onSelect} />);

    const trigger = screen.getByRole("button", { name: /sba-agentic/ });
    expect(trigger).toHaveTextContent("/Users/nathan/Developer/proj/sba-agentic");
    fireEvent.click(trigger);
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "worktrees/abc" } });

    const result = await screen.findByRole("option", { name: /sba-agentic/ });
    fireEvent.click(result);
    expect(onSelect).toHaveBeenCalledWith("sba-key");
  });

  it("supports keyboard selection and Escape dismissal", async () => {
    const onSelect = vi.fn();
    render(() => <ProjectPicker projects={projects} selectedProjectKey={undefined} onSelect={onSelect} />);
    const trigger = screen.getByRole("button", { name: /All projects/ });

    fireEvent.click(trigger);
    const search = screen.getByLabelText("Search projects");
    fireEvent.input(search, { target: { value: "sba" } });
    fireEvent.keyDown(search, { key: "Enter" });
    expect(onSelect).toHaveBeenCalledWith("sba-key");
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(trigger);
    fireEvent.keyDown(screen.getByLabelText("Search projects"), { key: "Escape" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("visually advances the active option and scrolls it into view with the keyboard", async () => {
    const onSelect = vi.fn();
    const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollIntoView");
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true,
      value: scrollIntoView,
    });

    try {
      render(() => <ProjectPicker projects={projects} selectedProjectKey={undefined} onSelect={onSelect} />);
      fireEvent.click(screen.getByRole("button", { name: /All projects/ }));
      const search = screen.getByLabelText("Search projects");
      const options = screen.getAllByRole("option");

      expect(options[0]).toHaveClass("project-picker-option--active");
      expect(options[1]).not.toHaveClass("project-picker-option--active");

      fireEvent.keyDown(search, { key: "ArrowDown" });

      expect(options[0]).not.toHaveClass("project-picker-option--active");
      expect(options[1]).toHaveClass("project-picker-option--active");
      expect(search).toHaveAttribute("aria-activedescendant", options[1]?.id);
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ block: "nearest" }));
      expect(scrollIntoView.mock.instances.at(-1)).toBe(options[1]);
      expect(onSelect).not.toHaveBeenCalled();
    } finally {
      if (originalScrollIntoView) {
        Object.defineProperty(HTMLElement.prototype, "scrollIntoView", originalScrollIntoView);
      } else {
        Reflect.deleteProperty(HTMLElement.prototype, "scrollIntoView");
      }
    }
  });
});
