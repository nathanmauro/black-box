import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal, type JSX } from "solid-js";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  getSavedMeld,
  getSavedMeldJson,
  getUnassignedBraids,
  type ProjectSavedMeld,
} from "../lib/api";
import SavedBraidsReader, { savedBraidHref } from "./SavedBraidsReader";

vi.mock("@solidjs/router", () => ({
  A: (props: JSX.AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} />,
}));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  getSavedMeld: vi.fn(),
  getSavedMeldJson: vi.fn(),
  getUnassignedBraids: vi.fn(),
}));

function braid(id = "braid-one"): ProjectSavedMeld {
  return {
    id,
    projectKey: null,
    canonicalKey: null,
    title: `Saved ${id}`,
    body: "  Full synthesis\n\n<script>plain evidence</script>\nFinal line.  ",
    provider: "caller-provider",
    model: "caller-model",
    promptVersion: "braid-v1",
    executionMode: "external",
    savedFromPreview: false,
    createdAt: "2026-10-03T12:34:56.123456789Z",
    metadata: { kind: "arbitrary-caller-value", uncheckedCitation: "caller says" },
    sessions: [
      {
        id: "source-b",
        source: "claude",
        clientSessionId: "client-b",
        title: "Source B",
        cwd: "/fixture/b",
        eventCount: 5,
        startedAt: "2026-10-01T01:00:00Z",
        lastSeenAt: "2026-10-02T01:00:00Z",
      },
      {
        id: "source-a",
        source: null,
        clientSessionId: null,
        title: null,
        cwd: null,
        eventCount: 0,
        startedAt: null,
        lastSeenAt: null,
      },
    ],
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}
beforeEach(() => {
  vi.mocked(getSavedMeld).mockReset().mockResolvedValue(braid());
  vi.mocked(getSavedMeldJson).mockReset().mockResolvedValue(JSON.stringify(braid()));
  vi.mocked(getUnassignedBraids)
    .mockReset()
    .mockResolvedValue({ items: [braid()], count: 1, nextBefore: null });
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("SavedBraidsReader", () => {
  it("reads a direct ID outside the page, preserves text and source order without treating metadata as proof", async () => {
    vi.mocked(getUnassignedBraids).mockResolvedValue({ items: [], count: 0, nextBefore: null });
    render(() => <SavedBraidsReader selectedId="braid-one" />);
    await screen.findByRole("heading", { name: "Saved braid-one" });
    expect(document.querySelector(".saved-braid-body")?.textContent).toBe(braid().body);
    expect(document.querySelector(".saved-braid-body script")).toBeNull();
    expect(screen.getByText("caller-provider")).toBeInTheDocument();
    expect(screen.getByText("Caller-declared provenance")).toBeInTheDocument();
    expect(screen.getByText(braid().createdAt)).toHaveAttribute("datetime", braid().createdAt);
    const links = within(
      document.querySelector(".saved-braid-sources") as HTMLElement,
    ).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/sessions/source-b?reveal=session",
      "/sessions/source-a?reveal=session",
    ]);
    expect(links.map((link) => link.textContent)).toEqual(["Source B", "source-a"]);
    expect(screen.queryByText("caller says")).not.toBeInTheDocument();
    expect(screen.getByText("No saved unassigned braids yet.")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Create|Run|Generate|Search/ }),
    ).not.toBeInTheDocument();
  });

  it("uses durable list links and loaded count instead of interpreting page count as total", async () => {
    vi.mocked(getUnassignedBraids)
      .mockResolvedValueOnce({ items: [braid()], count: 1, nextBefore: "page-two" })
      .mockResolvedValueOnce({ items: [braid("two")], count: 1, nextBefore: null });
    render(() => <SavedBraidsReader />);
    const link = await screen.findByRole("link", { name: /Saved braid-one/ });
    expect(link).toHaveAttribute("href", savedBraidHref("braid-one"));
    fireEvent.click(screen.getByRole("button", { name: "Load more saved braids" }));
    await screen.findByRole("link", { name: /Saved two/ });
    expect(screen.getByText("2 loaded")).toBeInTheDocument();
    expect(getUnassignedBraids).toHaveBeenLastCalledWith("page-two", expect.any(AbortSignal));
    expect(
      screen.queryByRole("button", { name: "Load more saved braids" }),
    ).not.toBeInTheDocument();
    expect(getSavedMeld).not.toHaveBeenCalled();
  });

  it("retains earlier pages on pagination failure and retries the same cursor", async () => {
    vi.mocked(getUnassignedBraids)
      .mockResolvedValueOnce({ items: [braid()], count: 1, nextBefore: "next" })
      .mockRejectedValueOnce(new Error("Temporary page failure"))
      .mockResolvedValueOnce({ items: [braid(), braid("two")], count: 2, nextBefore: null });
    render(() => <SavedBraidsReader />);
    fireEvent.click(await screen.findByRole("button", { name: "Load more saved braids" }));
    await screen.findByText("Temporary page failure");
    expect(screen.getByRole("link", { name: /Saved braid-one/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry saved braid list" }));
    await screen.findByRole("link", { name: /Saved two/ });
    expect(getUnassignedBraids).toHaveBeenLastCalledWith("next", expect.any(AbortSignal));
    expect(screen.getAllByRole("link", { name: /Saved braid-one/ })).toHaveLength(1);
    expect(screen.getByText("2 loaded")).toBeInTheDocument();
  });

  it("distinguishes list failure from empty and retries without a project catalog", async () => {
    vi.mocked(getUnassignedBraids)
      .mockRejectedValueOnce(new Error("List offline"))
      .mockResolvedValueOnce({ items: [], count: 0, nextBefore: null });
    render(() => <SavedBraidsReader />);
    await screen.findByText("List offline");
    expect(screen.queryByText("No saved unassigned braids yet.")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry saved braid list" }));
    await screen.findByText("No saved unassigned braids yet.");
  });

  it("shows an honest 404 and permits retry without dropping the selection", async () => {
    vi.mocked(getSavedMeld)
      .mockRejectedValueOnce(new ApiError("Not found", 404))
      .mockResolvedValueOnce(braid());
    render(() => <SavedBraidsReader selectedId="braid-one" />);
    await screen.findByText("This saved artifact could not be loaded (not found).");
    fireEvent.click(screen.getByRole("button", { name: "Retry saved artifact" }));
    await screen.findByRole("heading", { name: "Saved braid-one" });
    expect(getSavedMeld).toHaveBeenLastCalledWith("braid-one", expect.any(AbortSignal));
  });

  it.each(["resolve", "reject"] as const)(
    "ignores a stale detail %s after rapid selection",
    async (settle) => {
      const older = deferred<ProjectSavedMeld>();
      vi.mocked(getSavedMeld)
        .mockReturnValueOnce(older.promise)
        .mockResolvedValueOnce(braid("new"));
      const [id, setId] = createSignal("old");
      render(() => <SavedBraidsReader selectedId={id()} />);
      await waitFor(() => expect(getSavedMeld).toHaveBeenCalledTimes(1));
      const signal = vi.mocked(getSavedMeld).mock.calls[0][1];
      setId("new");
      await screen.findByRole("heading", { name: "Saved new" });
      expect(signal?.aborted).toBe(true);
      if (settle === "resolve") older.resolve(braid("old"));
      else older.reject(new Error("Stale failure"));
      await Promise.resolve();
      expect(screen.getByRole("heading", { name: "Saved new" })).toBeInTheDocument();
      expect(screen.queryByRole("heading", { name: "Saved old" })).not.toBeInTheDocument();
      expect(screen.queryByText("Stale failure")).not.toBeInTheDocument();
    },
  );

  it("does not steal newer keyboard focus when a delayed detail response settles", async () => {
    const pending = deferred<ProjectSavedMeld>();
    vi.mocked(getSavedMeld).mockReturnValue(pending.promise);
    render(() => (
      <>
        <button type="button">Other navigation</button>
        <SavedBraidsReader selectedId="braid-one" />
      </>
    ));
    await screen.findByText("Loading saved artifact…");
    const navigation = screen.getByRole("button", { name: "Other navigation" });
    navigation.focus();
    pending.resolve(braid());
    await screen.findByRole("heading", { name: "Saved braid-one" });
    expect(navigation).toHaveFocus();
  });

  it("closes the narrow chooser when the current item is selected again", async () => {
    vi.stubGlobal("matchMedia", () => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    render(() => <SavedBraidsReader selectedId="braid-one" />);
    await screen.findByRole("heading", { name: "Saved braid-one" });
    const chooser = screen.getByRole("button", { name: "Choose a saved braid" });
    fireEvent.click(chooser);
    const current = screen.getByRole("link", { name: /Saved braid-one/ });
    current.focus();
    fireEvent.click(current);
    expect(chooser).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("navigation", { name: "Saved braids" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Saved artifact")).toHaveFocus();
    expect(getSavedMeld).toHaveBeenCalledTimes(1);
  });

  it("distinguishes project-owned direct IDs even if metadata claims braid", async () => {
    vi.mocked(getSavedMeld).mockResolvedValue({
      ...braid(),
      projectKey: "project/one",
      canonicalKey: "/fixture/one",
      metadata: { kind: "braid" },
    });
    render(() => <SavedBraidsReader selectedId="ordinary" />);
    await screen.findByText("This saved meld belongs to a project; it is not an unassigned braid.");
    expect(screen.getByRole("link", { name: "Open owning project" })).toHaveAttribute(
      "href",
      "/projects/project%2Fone",
    );
    expect(document.querySelector(".saved-braid-body")).toBeNull();
  });

  it("preserves the saved JSON large integer in the downloadable evidence", async () => {
    const rawMetadata = '{"largeInteger":9007199254740993}';
    const rawArtifact = '{"id":"braid-one","metadata":' + rawMetadata + "}";
    vi.mocked(getSavedMeldJson).mockResolvedValue(rawArtifact);
    vi.mocked(getSavedMeld).mockResolvedValue({ ...braid(), metadata: JSON.parse(rawMetadata) });
    const createObjectURL = vi.fn((_blob: Blob) => "blob:fixture-artifact");
    vi.stubGlobal(
      "URL",
      Object.assign(class extends URL {}, { createObjectURL, revokeObjectURL: vi.fn() }),
    );
    render(() => <SavedBraidsReader selectedId="braid-one" />);
    await screen.findByRole("heading", { name: "Saved braid-one" });
    const details = screen.getByText("Caller metadata (unverified)").closest("details")!;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Download saved artifact JSON" }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalled());
    const blob = createObjectURL.mock.calls[0][0];
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.readAsText(blob);
    });
    expect(text).toBe(rawArtifact);
    expect(text).toMatch(/"largeInteger"\s*:\s*9007199254740993/);
  });

  it("mounts a bounded parsed metadata preview only on disclosure with download error/retry", async () => {
    const huge = { note: "x".repeat(23_987) + "😀".repeat(2000) };
    vi.mocked(getSavedMeld).mockResolvedValue({ ...braid(), metadata: huge });
    const createObjectURL = vi.fn(() => "blob:fixture-metadata");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", Object.assign(class extends URL {}, { createObjectURL, revokeObjectURL }));
    const mounted = render(() => <SavedBraidsReader selectedId="braid-one" />);
    await screen.findByRole("heading", { name: "Saved braid-one" });
    expect(document.querySelector(".saved-braid-metadata")).toBeNull();
    const details = screen.getByText("Caller metadata (unverified)").closest("details")!;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    const preview = document.querySelector(".saved-braid-metadata")!.textContent!;
    expect(preview.length).toBeLessThanOrEqual(24_000);
    expect(preview).not.toMatch(/[\uD800-\uDBFF]$/);
    expect(screen.getByText(/Metadata preview truncated/)).toBeInTheDocument();
    expect(createObjectURL).not.toHaveBeenCalled();
    vi.mocked(getSavedMeldJson)
      .mockRejectedValueOnce(new ApiError("Download unavailable", 503))
      .mockResolvedValueOnce('{"id":"braid-one"}');
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Download saved artifact JSON" }));
    await screen.findByRole("alert");
    expect(screen.getByText("Download unavailable")).toBeInTheDocument();
    expect(createObjectURL).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Download saved artifact JSON" }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob)));
    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith("blob:fixture-metadata"));
    mounted.unmount();
  });
});
