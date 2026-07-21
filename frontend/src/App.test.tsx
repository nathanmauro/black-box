import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import App from "./App";

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; class?: string; "aria-label"?: string; children: JSX.Element }) => (
    <a href={props.href} class={props.class} aria-label={props["aria-label"]}>
      {props.children}
    </a>
  ),
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: "/" }),
  useSearchParams: () => [{}],
}));

vi.mock("./lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./lib/api")>();
  return {
    ...actual,
    getSessions: vi.fn(async () => []),
    search: vi.fn(async () => ({
      query: "",
      local: [],
      elastic: [],
      elasticHealth: {},
    })),
  };
});

describe("App shell", () => {
  it("renders secondary controls in a compact utility header", async () => {
    render(() => (
      <App>
        <section aria-label="Current page">Page content</section>
      </App>
    ));

    const utilityBar = screen.getByRole("banner", { name: "Black Box utility bar" });
    expect(utilityBar).toHaveClass("app-utility-bar");
    expect(within(utilityBar).getByRole("link", { name: "Black Box overview" })).toHaveAttribute("href", "/");

    const utilityNav = within(utilityBar).getByRole("navigation", { name: "Utility" });
    expect(within(utilityNav).getByRole("link", { name: "Stream" })).toHaveAttribute("href", "/");
    expect(within(utilityNav).getByRole("link", { name: "Browse" })).toHaveAttribute("href", "/?view=browse");
    expect(within(utilityNav).getByRole("link", { name: "Projects" })).toHaveAttribute("href", "/projects");
    expect(within(utilityNav).getByRole("link", { name: "Recall" })).toHaveAttribute("href", "/recall");
    expect(within(utilityNav).queryByRole("link", { name: "Search" })).not.toBeInTheDocument();
    expect(within(utilityNav).getByRole("link", { name: "Board" })).toHaveAttribute("href", "/board");
    expect(within(utilityNav).queryByRole("link", { name: "Sessions" })).not.toBeInTheDocument();
    expect(within(utilityNav).queryByRole("link", { name: "Overview" })).not.toBeInTheDocument();
    expect(within(utilityNav).queryByRole("link", { name: "Stats" })).not.toBeInTheDocument();
    expect(within(utilityNav).queryByRole("link", { name: "Graph" })).not.toBeInTheDocument();

    fireEvent.click(within(utilityBar).getByRole("button", { name: "Filter sources" }));
    expect(within(utilityBar).getByRole("group", { name: "Filter by source" })).toBeInTheDocument();
    expect(within(utilityBar).getByText("down")).toBeInTheDocument();
    expect(within(utilityBar).getByRole("button", { name: "Open command palette" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Current page" })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "k", metaKey: true });
    const palette = await screen.findByRole("dialog", { name: "Command palette" });
    expect(within(palette).getByRole("option", { name: /Activity/i })).toBeInTheDocument();
    expect(within(palette).getByRole("option", { name: /Board/i })).toBeInTheDocument();
    expect(within(palette).getByRole("option", { name: /Projects/i })).toBeInTheDocument();
    expect(within(palette).queryByRole("option", { name: /Overview/i })).not.toBeInTheDocument();
  });
});
