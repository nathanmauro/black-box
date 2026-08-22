import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { sourceFilter } from "../lib/stores";
import SourceChips from "./SourceChips";

type SearchParams = { q?: string; view?: string };

let pathname: string;
let params: SearchParams;
let setParams: SetStoreFunction<SearchParams>;

vi.mock("@solidjs/router", () => ({
  useLocation: () => ({
    get pathname() {
      return pathname;
    },
  }),
  useSearchParams: () => [params, setParams],
}));

beforeEach(() => {
  pathname = "/";
  [params, setParams] = createStore<SearchParams>({});
  sourceFilter.clear();
});

describe("SourceChips (mode-aware Sources menu)", () => {
  it("writes the source facet into the URL q on the Stream surface", () => {
    [params, setParams] = createStore<SearchParams>({ q: "kind:Decision" });
    render(() => <SourceChips />);

    fireEvent.click(screen.getByRole("button", { name: "Codex" }));
    expect(params.q).toBe("source:codex kind:Decision");

    fireEvent.click(screen.getByRole("button", { name: "Claude" }));
    expect(params.q).toBe("source:codex,claude kind:Decision");

    // The client-side signal stays untouched on the Stream — q is the one filter language.
    expect(sourceFilter.selected().size).toBe(0);
  });

  it("derives checkmarks from the parsed q on /stream and clears through it", () => {
    pathname = "/stream";
    [params, setParams] = createStore<SearchParams>({ q: "source:codex,claude" });
    render(() => <SourceChips />);

    expect(screen.getByRole("button", { name: "Codex" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Claude" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Cursor" })).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(screen.getByRole("button", { name: "Codex" }));
    expect(params.q).toBe("source:claude");

    fireEvent.click(screen.getByRole("button", { name: "All" }));
    expect(params.q).toBeUndefined();
  });

  it("matches source values case-insensitively like the grammar", () => {
    [params, setParams] = createStore<SearchParams>({ q: "source:Codex" });
    render(() => <SourceChips />);

    expect(screen.getByRole("button", { name: "Codex" })).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(screen.getByRole("button", { name: "Codex" }));
    expect(params.q).toBeUndefined();
  });

  it("counts NOT-source exclusions as an active selection and clears them too", () => {
    [params, setParams] = createStore<SearchParams>({ q: "NOT source:codex kind:Decision" });
    render(() => <SourceChips />);

    // An exclusion is not "All": the All chip must not claim an unfiltered view.
    expect(screen.getByRole("button", { name: "All" })).not.toHaveClass("source-chip--active");

    fireEvent.click(screen.getByRole("button", { name: "All" }));
    expect(params.q).toBe("kind:Decision");
    expect(screen.getByRole("button", { name: "All" })).toHaveClass("source-chip--active");
  });

  it("keeps the client-side signal behavior on non-stream surfaces", () => {
    pathname = "/recall";
    render(() => <SourceChips />);

    fireEvent.click(screen.getByRole("button", { name: "Codex" }));
    expect(sourceFilter.selected().has("codex")).toBe(true);
    expect(params.q).toBeUndefined();

    fireEvent.click(screen.getByRole("button", { name: "All" }));
    expect(sourceFilter.selected().size).toBe(0);
  });

  it("treats Browse on / as a non-stream surface", () => {
    [params, setParams] = createStore<SearchParams>({ view: "browse" });
    render(() => <SourceChips />);

    fireEvent.click(screen.getByRole("button", { name: "Claude" }));
    expect(sourceFilter.selected().has("claude")).toBe(true);
    expect(params.q).toBeUndefined();
  });
});
