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
    expect(within(listbox).getByText("~/Developer/proj/sba-agentic")).toBeInTheDocument();

    fireEvent.click(within(listbox).getByRole("option", { name: /sba-agentic/ }));
    expect(onSelect).toHaveBeenCalledWith("sba-key");

    fireEvent.click(screen.getByRole("button", { name: /sba-agentic/ }));
    fireEvent.click(screen.getByRole("button", { name: "All projects" }));
    expect(onSelect).toHaveBeenLastCalledWith(undefined);
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
});
