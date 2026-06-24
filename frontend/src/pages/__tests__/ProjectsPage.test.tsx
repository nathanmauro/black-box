import { render, screen } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { getProjectMelds, getProjects, previewProjectMeld, saveProjectMeld } from "../../lib/api";
import ProjectsPage from "../ProjectsPage";

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; children: JSX.Element }) => <a href={props.href}>{props.children}</a>,
  useNavigate: () => vi.fn(),
  useParams: () => ({}),
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getProjects: vi.fn(async () => []),
    getProjectMelds: vi.fn(async () => []),
    previewProjectMeld: vi.fn(async () => {
      throw new Error("Project meld preview should stay parked");
    }),
    saveProjectMeld: vi.fn(async () => {
      throw new Error("Project meld save should stay parked");
    }),
  };
});

describe("ProjectsPage", () => {
  it("parks the project meld workspace without loading project or meld data", () => {
    render(() => <ProjectsPage />);

    expect(screen.getByRole("heading", { name: "Projects are parked" })).toBeInTheDocument();
    expect(screen.getByText(/Project storylines and melds are disabled/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open Sessions" })).toHaveAttribute("href", "/sessions");
    expect(screen.getByRole("link", { name: "Open Search" })).toHaveAttribute("href", "/search");
    expect(screen.queryByRole("button", { name: /Preview meld/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/meld builder/i)).not.toBeInTheDocument();
    expect(getProjects).not.toHaveBeenCalled();
    expect(getProjectMelds).not.toHaveBeenCalled();
    expect(previewProjectMeld).not.toHaveBeenCalled();
    expect(saveProjectMeld).not.toHaveBeenCalled();
  });
});
