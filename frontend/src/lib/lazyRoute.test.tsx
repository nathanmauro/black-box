import { render, screen, waitFor } from "@solidjs/testing-library";
import { createResource, Match, Switch, type Component } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { lazyRoute } from "./lazyRoute";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });

  return { promise, resolve, reject };
}

describe("lazyRoute", () => {
  it("shows module loading separately from the page's API loading and error states", async () => {
    const module = deferred<{ default: Component<{ title: string }> }>();
    const response = deferred<string>();

    const Page: Component<{ title: string }> = (props) => {
      const [data] = createResource(() => response.promise);

      return (
        <section>
          <h1>{props.title}</h1>
          <Switch>
            <Match when={data.loading}>Loading page data…</Match>
            <Match when={data.error}>Page API unavailable</Match>
            <Match when={data()}>{data()}</Match>
          </Switch>
        </section>
      );
    };

    const Route = lazyRoute(() => module.promise);

    render(() => <Route title="Project details" />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading view…");

    module.resolve({ default: Page });
    await screen.findByRole("heading", { name: "Project details" });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByText("Loading page data…")).toBeVisible();

    response.reject(new Error("API offline"));
    await screen.findByText("Page API unavailable");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("offers a page reload when the module cannot load", async () => {
    const module = deferred<{ default: Component }>();
    const Route = lazyRoute(() => module.promise);

    render(() => <Route />);
    module.reject(new Error("Failed to fetch dynamically imported module"));

    expect(await screen.findByRole("alert")).toHaveTextContent("This view could not be loaded.");
    expect(screen.getByRole("button", { name: "Reload view" })).toBeVisible();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("reuses the loaded module on repeated navigation", async () => {
    const load = vi.fn(async () => ({ default: () => <h1>Ideas</h1> }));
    const Route = lazyRoute(load);
    const first = render(() => <Route />);

    await screen.findByRole("heading", { name: "Ideas" });
    first.unmount();
    render(() => <Route />);

    expect(screen.getByRole("heading", { name: "Ideas" })).toBeVisible();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("can return to a route whose module was still loading when it was unmounted", async () => {
    const module = deferred<{ default: Component }>();
    const load = vi.fn(() => module.promise);
    const Route = lazyRoute(load);
    const first = render(() => <Route />);

    expect(screen.getByRole("status")).toBeVisible();
    first.unmount();
    render(() => <Route />);
    module.resolve({ default: () => <h1>Projects</h1> });

    await waitFor(() => expect(screen.getByRole("heading", { name: "Projects" })).toBeVisible());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(load).toHaveBeenCalledTimes(1);
  });
});
