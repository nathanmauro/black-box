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
  it("renders primary navigation and global controls in a static sidebar", async () => {
    render(() => (
      <App>
        <section aria-label="Current page">Page content</section>
      </App>
    ));

    const sidebar = screen.getByRole("complementary", { name: "Application navigation" });
    expect(sidebar).toHaveClass("app-sidebar");
    expect(within(sidebar).getByRole("link", { name: "Black Box overview" })).toHaveAttribute("href", "/");

    const primaryNav = within(sidebar).getByRole("navigation", { name: "Primary" });
    expect(within(primaryNav).getByRole("link", { name: "Overview" })).toHaveAttribute("href", "/");
    expect(within(primaryNav).getByRole("link", { name: "Sessions" })).toHaveAttribute("href", "/sessions");
    expect(within(primaryNav).getByRole("link", { name: "Search" })).toHaveAttribute("href", "/search");

    expect(within(sidebar).getByRole("group", { name: "Filter by source" })).toBeInTheDocument();
    expect(within(sidebar).getByText("down")).toBeInTheDocument();
    expect(within(sidebar).getByRole("button", { name: "Open command palette" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Current page" })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "k", metaKey: true });
    expect(await screen.findByRole("dialog", { name: "Command palette" })).toBeInTheDocument();
  });
});
