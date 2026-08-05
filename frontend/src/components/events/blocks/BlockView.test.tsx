import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { CodeNavigationContext } from "../../../lib/codeNavigation";
import BlockView from "./BlockView";

describe("BlockView diff", () => {
  it("keeps diff content unmounted until the details block is opened, then renders hunks", () => {
    const { container } = render(() => (
      <BlockView
        eventId="evt-1"
        index={0}
        block={{ kind: "diff", file: { path: "/tmp/a.ts" }, oldText: "const a = 1;", newText: "const a = 2;", label: "Diff (12 → 12 chars)" }}
      />
    ));
    expect(screen.getByText("Diff (12 → 12 chars)")).toBeInTheDocument();
    expect(container.querySelector(".diff-line")).toBeNull(); // lazy: nothing mounted yet

    const details = container.querySelector("details") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));

    expect(container.querySelector(".diff-line--del")?.textContent).toContain("const a = 1;");
    expect(container.querySelector(".diff-line--add")?.textContent).toContain("const a = 2;");
  });
});

describe("BlockView bash", () => {
  it("shows the command immediately and the output behind a sized details block", () => {
    const { container } = render(() => (
      <BlockView
        eventId="evt-2"
        index={0}
        block={{ kind: "bash", command: "npm test", cwd: "/tmp/proj", output: "42 tests passed", exitCode: 0, wallTime: "1.2 seconds" }}
      />
    ));
    expect(screen.getByText("npm test")).toBeInTheDocument();
    expect(screen.getByText("exit 0")).toBeInTheDocument();
    expect(screen.getByText("Output (15 chars)")).toBeInTheDocument();
    expect(screen.queryByText("42 tests passed")).toBeNull(); // lazy

    const details = container.querySelector("details") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    expect(screen.getByText("42 tests passed")).toBeInTheDocument();
  });
});

describe("BlockView patch", () => {
  it("renders parsed patch hunks per file when opened", () => {
    const command = "*** Begin Patch\n*** Update File: /tmp/a.ts\n@@\n-old\n+new\n*** End Patch";
    const { container } = render(() => (
      <BlockView eventId="evt-3" index={0} block={{ kind: "patch", command, files: [{ op: "update", path: "/tmp/a.ts", movedTo: null }] }} />
    ));
    const details = container.querySelector("details") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    expect(container.querySelector(".diff-line--del")?.textContent).toContain("old");
    expect(container.querySelector(".diff-line--add")?.textContent).toContain("new");
  });

  it("gives every absolute patch path and move target the shared file actions", () => {
    const command = "*** Begin Patch\n*** Update File: /repo/old.ts\n*** Move to: /repo/new.ts\n@@\n-old\n+new\n*** End Patch";
    const { container } = render(() => (
      <CodeNavigationContext.Provider
        value={{
          scopes: () => [{ projectKey: "repo-key", root: "/repo" }],
          catalogStatus: () => "ready",
          catalogError: () => null,
          refreshCatalog: () => undefined,
        }}
      >
        <BlockView
          eventId="evt-move"
          index={0}
          block={{ kind: "patch", command, files: [{ op: "update", path: "/repo/old.ts", movedTo: "/repo/new.ts" }] }}
        />
      </CodeNavigationContext.Provider>
    ));
    const details = container.querySelector("details") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));

    expect(screen.getByRole("button", { name: "Open /repo/old.ts in editor" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open /repo/new.ts in editor" })).toBeInTheDocument();
  });
});

describe("BlockView fallback", () => {
  it("renders the existing ToolPayload for fallback blocks", () => {
    render(() => (
      <BlockView eventId="evt-4" index={0} block={{ kind: "fallback", toolName: "Mystery", inputJson: JSON.stringify({ query: "hello" }), outputJson: null }} />
    ));
    expect(screen.getByText("Query")).toBeInTheDocument();
    expect(screen.getByText("hello")).toBeInTheDocument();
  });
});
