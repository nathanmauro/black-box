import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ApiError, type CodeProjectScope } from "../../lib/api";
import { CodeNavigationContext, type CodeCatalogStatus } from "../../lib/codeNavigation";
import FileReferenceActions from "./FileReferenceActions";

const mocks = vi.hoisted(() => ({
  openInEditor: vi.fn(),
  revealInFinder: vi.fn(),
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    openInEditor: mocks.openInEditor,
    revealInFinder: mocks.revealInFinder,
  };
});

const scopes: CodeProjectScope[] = [{ projectKey: "repo-key", root: "/repo" }];
const refreshCatalog = vi.fn();

beforeEach(() => {
  refreshCatalog.mockReset();
  mocks.openInEditor.mockReset();
  mocks.openInEditor.mockResolvedValue({ status: "opened" });
  mocks.revealInFinder.mockReset();
  mocks.revealInFinder.mockResolvedValue({ status: "revealed" });
});

afterEach(() => {
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined });
});

it("sends only the catalog key and relative path when opening a resolved file", async () => {
  renderWithScopes(() => (
    <FileReferenceActions file={{ path: "/repo/src/App.ts", line: 4 }} label="src/App.ts" />
  ));

  fireEvent.click(screen.getByRole("button", { name: "Open /repo/src/App.ts in editor" }));

  await waitFor(() =>
    expect(mocks.openInEditor).toHaveBeenCalledWith({
      projectKey: "repo-key",
      relativePath: "src/App.ts",
      line: 4,
    }),
  );
  expect(await screen.findByText("Opened in editor.")).toBeInTheDocument();
  expect(JSON.stringify(mocks.openInEditor.mock.calls[0]?.[0])).not.toContain("/repo");
});

it("keeps copy visible but disables open and reveal for an unresolved path", async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
  renderWithScopes(() => <FileReferenceActions file={{ path: "/etc/passwd" }} />);

  expect(
    screen.queryByRole("button", { name: "Open /etc/passwd in editor" }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Reveal in Finder /etc/passwd" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
  expect(screen.getByText("This path is outside the eligible project roots.")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Copy path /etc/passwd" }));

  await waitFor(() => expect(writeText).toHaveBeenCalledWith("/etc/passwd"));
  expect(await screen.findByText("Path copied.")).toBeInTheDocument();
  expect(mocks.openInEditor).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole("button", { name: "Refresh eligible project roots" }));
  expect(refreshCatalog).toHaveBeenCalledOnce();
});

it("distinguishes loading and failed catalogs and offers an accessible retry", () => {
  const { unmount } = renderWithScopes(
    () => <FileReferenceActions file={{ path: "/repo/app.ts" }} />,
    [],
    "loading",
  );
  expect(screen.getByText("Checking eligible project roots.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Refresh eligible project roots" })).toBeDisabled();
  unmount();

  renderWithScopes(
    () => <FileReferenceActions file={{ path: "/repo/app.ts" }} />,
    [],
    "error",
    "Catalog request failed.",
  );
  expect(screen.getByText("Catalog request failed.")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Retry eligible project roots" }));
  expect(refreshCatalog).toHaveBeenCalledOnce();
});

it("reports clipboard rejection without hiding the copy action", async () => {
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
  });
  renderWithScopes(() => <FileReferenceActions file={{ path: "/etc/passwd" }} />);

  fireEvent.click(screen.getByRole("button", { name: "Copy path /etc/passwd" }));

  expect(await screen.findByText("Could not copy path.")).toBeInTheDocument();
});

it("reveals a resolved file through the relative reference and reports success", async () => {
  renderWithScopes(() => <FileReferenceActions file={{ path: "/repo/src/App.ts", line: 4 }} />);

  fireEvent.click(screen.getByRole("button", { name: "Reveal in Finder /repo/src/App.ts" }));

  await waitFor(() =>
    expect(mocks.revealInFinder).toHaveBeenCalledWith({
      projectKey: "repo-key",
      relativePath: "src/App.ts",
      line: 4,
    }),
  );
  expect(await screen.findByText("Revealed in Finder.")).toBeInTheDocument();
  expect(JSON.stringify(mocks.revealInFinder.mock.calls[0]?.[0])).not.toContain("/repo");
});

it.each([
  ["file_missing", 404, "File no longer exists."],
  ["outside_project_root", 403, "Blocked: path is outside the project root."],
  ["project_unresolved", 409, "Project root is no longer available."],
  ["editor_disabled", 503, "Editor integration is unavailable."],
  ["invalid_reference", 400, "File location is invalid."],
])("renders the %s backend failure as honest local status", async (type, status, expected) => {
  mocks.openInEditor.mockRejectedValue(new ApiError("Navigation failed.", status, type));
  renderWithScopes(() => <FileReferenceActions file={{ path: "/repo/gone.ts" }} />);

  fireEvent.click(screen.getByRole("button", { name: "Open /repo/gone.ts in editor" }));

  expect(await screen.findByText(expected)).toBeInTheDocument();
});

it("renders a typed Finder failure as honest local status", async () => {
  mocks.revealInFinder.mockRejectedValue(
    new ApiError("Finder unavailable.", 503, "reveal_unavailable"),
  );
  renderWithScopes(() => <FileReferenceActions file={{ path: "/repo/gone.ts" }} />);

  fireEvent.click(screen.getByRole("button", { name: "Reveal in Finder /repo/gone.ts" }));

  expect(await screen.findByText("Finder integration is unavailable.")).toBeInTheDocument();
});

function renderWithScopes(
  view: () => unknown,
  currentScopes: CodeProjectScope[] = scopes,
  status: CodeCatalogStatus = "ready",
  error: string | null = null,
) {
  return render(() => (
    <CodeNavigationContext.Provider
      value={{
        scopes: () => currentScopes,
        catalogStatus: () => status,
        catalogError: () => error,
        refreshCatalog,
      }}
    >
      {view() as never}
    </CodeNavigationContext.Provider>
  ));
}
