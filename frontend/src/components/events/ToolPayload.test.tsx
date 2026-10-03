import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import ToolPayload from "./ToolPayload";
import EventRow, { ReaderText } from "./EventRow";

function toggle(details: HTMLDetailsElement, open: boolean) {
  details.open = open;
  fireEvent(details, new Event("toggle"));
}
const imageData = "QUJD".repeat(5000);
const original = JSON.stringify({
  content: [
    { type: "image", image_url: { url: `data:image/jpeg;base64,${imageData}` } },
    { type: "text", text: "Readable screenshot explanation" },
  ],
  resultId: "result-123",
  isError: false,
});

describe("generic tool disclosure", () => {
  it("renders nothing for missing, nullable, or blank payloads", () => {
    for (const raw of [undefined, null, "", "  "] as const) {
      const view = render(() => <ToolPayload inputJson={raw} outputJson={raw} />);
      expect(view.container).toBeEmptyDOMElement();
      view.unmount();
    }
  });
  it("preserves the existing readable literal null text", () => {
    render(() => <ToolPayload outputJson="null" />);
    expect(screen.getByText("null")).toBeInTheDocument();
  });
  it("keeps MCP media unmounted, then compact, until exact original access is requested", () => {
    const { container } = render(() => (
      <EventRow
        event={{
          id: "mcp-1",
          sessionId: "session-1",
          clientSessionId: "fixture-client",
          source: "codex",
          eventType: "PostToolUse",
          observedAt: "2026-10-03T00:00:00Z",
          toolName: "MCP__CUA_REPL__JS",
          toolInputJson: '{"code":"inspect fixture"}',
          toolOutputJson: original,
        }}
      />
    ));
    const outer = container.querySelector("details") as HTMLDetailsElement;
    expect(outer.open).toBe(false);
    expect(container.textContent).not.toContain(imageData);
    expect(container.textContent).not.toContain("Readable screenshot explanation");
    expect(container.querySelector(".tool-payload-block--json")).toBeNull();
    expect(
      screen.getByText("inspect fixture", { selector: ".tool-payload-inline" }),
    ).toBeInTheDocument();
    toggle(outer, true);
    for (const text of [
      "Readable screenshot explanation",
      "result-123",
      "[Embedded image/jpeg; 20,000 base64 chars]",
    ])
      expect(container.textContent).toContain(text);
    expect(container.textContent).not.toContain(imageData);
    expect(container.querySelector(".tool-payload-original")).toBeNull();
    const raw = outer.querySelector("details") as HTMLDetailsElement;
    expect(raw.querySelector("summary")?.textContent).toMatch(/^Original result/);
    toggle(raw, true);
    expect(container.querySelector(".tool-payload-original")?.textContent).toBe(original);
    toggle(raw, false);
    expect(raw.open).toBe(false);
    toggle(outer, false);
    expect(outer.open).toBe(false);
  });
  it("keeps deep JSON safe to preview and makes the exact original accessible", () => {
    const raw = '{"item":'.repeat(2200) + '"readable"' + "}".repeat(2200);
    const { container } = render(() => <ToolPayload outputJson={raw} />);
    const outer = container.querySelector("details") as HTMLDetailsElement;
    toggle(outer, true);
    expect(
      screen.getByText("Deeply nested content is shortened in this preview."),
    ).toBeInTheDocument();
    expect(container.textContent).toContain("Nested content omitted after 64 levels");
    expect(container.textContent).not.toContain("Embedded media is shortened");
    toggle(outer.querySelector("details") as HTMLDetailsElement, true);
    expect(container.querySelector(".tool-payload-original")?.textContent).toBe(raw);
  });
  it("lazily discloses large inputs and retains exact original whitespace", () => {
    const input = ` { "command" : "${"x".repeat(1500)}" } `;
    const { container } = render(() => <ToolPayload inputJson={input} />);
    expect(container.querySelector("pre")).toBeNull();
    const outer = container.querySelector("details") as HTMLDetailsElement;
    expect(outer.querySelector("summary")?.textContent).toMatch(/^Input/);
    toggle(outer, true);
    toggle(outer.querySelector("details") as HTMLDetailsElement, true);
    expect(container.querySelector(".tool-payload-original")?.textContent).toBe(input);
  });
  it("keeps small ordinary sections direct", () => {
    const { container } = render(() => (
      <ToolPayload
        inputJson='{"query":"readable input"}'
        outputJson='{"status":"ok","output":"readable result"}'
      />
    ));
    expect(screen.getByText("readable input")).toBeInTheDocument();
    expect(screen.getByText("readable result")).toBeInTheDocument();
    expect(container.querySelector("details")).toBeNull();
  });
  it("compacts short typed media and retains original access", () => {
    const raw = '{"type":"image","mimeType":"image/png","data":"QUJD"}';
    const { container } = render(() => <ToolPayload outputJson={raw} />);
    expect(container.textContent).not.toContain("QUJD");
    expect(container.textContent).toContain("[Embedded image/png; 4 base64 chars]");
    toggle(container.querySelector("details") as HTMLDetailsElement, true);
    expect(container.querySelector(".tool-payload-original")?.textContent).toBe(raw);
  });
});

describe("numeric preview original access", () => {
  it.each([false, true])(
    "offers exact original input and result for inexact JSON (double encoded: %s)",
    (doubleEncoded) => {
      const plain = ' { "reference" : 9007199254740993 } ';
      const raw = doubleEncoded ? JSON.stringify(plain) : plain;
      const { container } = render(() => <ToolPayload inputJson={raw} outputJson={raw} />);
      expect(
        screen.getAllByText(
          "Numeric values changed in this preview. Open Original for exact captured text.",
        ),
      ).toHaveLength(2);
      expect(container.querySelectorAll(".tool-payload-original")).toHaveLength(0);
      const originals = container.querySelectorAll("details");
      expect(originals).toHaveLength(2);
      for (const details of originals) {
        toggle(details, true);
        expect(details.querySelector(".tool-payload-original")?.textContent).toBe(raw);
        toggle(details, false);
        expect(details.open).toBe(false);
      }
    },
  );
  it("does not add numeric warnings or disclosures for safe, quoted, or malformed small payloads", () => {
    for (const raw of [
      '{"n":0.1,"label":"9007199254740993"}',
      '{"n":9007199254740993,}',
      JSON.stringify(JSON.stringify('{"n":9007199254740993}')),
    ]) {
      const view = render(() => <ToolPayload inputJson={raw} outputJson={raw} />);
      expect(view.container.querySelector(".tool-payload-note")).toBeNull();
      expect(view.container.querySelector("details")).toBeNull();
      view.unmount();
    }
  });
  it("warns about unverified large previews only after disclosure and retains the original", () => {
    const raw = JSON.stringify({ text: "x".repeat(150_000) });
    const { container } = render(() => <ToolPayload outputJson={raw} />);
    expect(container.querySelector(".tool-payload-note")).toBeNull();
    const outer = container.querySelector("details")!;
    toggle(outer, true);
    expect(
      screen.getByText(
        "Numeric precision could not be checked in this preview. Open Original for exact captured text.",
      ),
    ).toBeInTheDocument();
    toggle(outer.querySelector("details")!, true);
    expect(container.querySelector(".tool-payload-original")?.textContent).toBe(raw);
  });
  it("offers original access for legacy exit-code rounding and negative zero", () => {
    for (const raw of [
      JSON.stringify("Exit code: 9007199254740993\nWall time: 1 second\nOutput:\ndone"),
      '{"n":-0}',
    ]) {
      const view = render(() => <ToolPayload outputJson={raw} />);
      expect(view.container.textContent).toContain("Numeric values changed in this preview");
      const original = view.container.querySelector("details")!;
      toggle(original, true);
      expect(view.container.querySelector(".tool-payload-original")?.textContent).toBe(raw);
      view.unmount();
    }
  });
});

describe("ReaderText accessible excerpt", () => {
  it("removes hidden text from DOM until expansion and restores the exact original", () => {
    const text = "visible ".repeat(150) + "SECRET_TAIL";
    const { container } = render(() => <ReaderText text={text} />);
    const button = screen.getByRole("button", { name: "Show full message" });
    const paragraph = container.querySelector("p")!;
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(button).toHaveAttribute("aria-controls", paragraph.id);
    expect(container.textContent).not.toContain("SECRET_TAIL");
    expect(paragraph.textContent!.length).toBeLessThanOrEqual(901);
    fireEvent.click(button);
    expect(paragraph.textContent).toBe(text);
    expect(button).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(container.textContent).not.toContain("SECRET_TAIL");
  });
  it("honors external expansion and preserves short messages without controls", () => {
    const text = "long ".repeat(500);
    const first = render(() => <ReaderText text={text} expanded />);
    expect(first.container.querySelector("p")?.textContent).toBe(text);
    expect(screen.getByRole("button")).toHaveAttribute("aria-expanded", "true");
    first.unmount();
    const short = "  exact short\nmessage  ";
    const second = render(() => <ReaderText text={short} />);
    expect(second.container.querySelector("p")?.textContent).toBe(short);
    expect(screen.queryByRole("button")).toBeNull();
  });
});
