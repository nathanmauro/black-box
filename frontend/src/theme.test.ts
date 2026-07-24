import { describe, expect, it } from "vitest";

// Vitest runs this contract in Node, while the shipped frontend intentionally
// does not include Node globals in its TypeScript surface.
// @ts-expect-error Node built-ins are available in Vitest.
const { readFileSync } = await import("node:fs");

const css = readFileSync("src/theme.css", "utf8") as string;

function expectRule(selector: string, declarations: string[]) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = css.match(new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{(?<body>[^}]*)\\}`, "s"));
  expect(match?.groups?.body, `missing CSS rule for ${selector}`).toBeDefined();
  const body = match?.groups?.body ?? "";
  for (const declaration of declarations) {
    expect(body, `${selector} should include ${declaration}`).toContain(declaration);
  }
}

describe("theme mobile layout contracts", () => {
  it("keeps the Activity session rail and reader inside bounded mobile panes", () => {
    expect(css).toContain("@media (max-width: 880px)");
    expect(css).toContain(".activity-workspace > .sessions-page");
    expect(css).toContain("height: calc(100dvh - 224px)");
    expect(css).toContain("grid-template-rows: minmax(188px, 34%) minmax(0, 1fr)");
    expect(css).toContain(".activity-workspace > .sessions-page .session-list-pane");
    expect(css).toContain(".activity-workspace > .sessions-page .session-detail-pane");
    expect(css).toContain(".activity-workspace > .sessions-page .session-rows");
    expect(css).toContain(".activity-workspace > .sessions-page .timeline-pane");
  });
});

describe("theme reader scrollbar contracts", () => {
  it("keeps reader panes dark, stable, and free of horizontal scroll", () => {
    expect(css).toContain("--scrollbar-track: transparent;");
    expect(css).toContain("--scrollbar-thumb:");
    expect(css).toContain("--scrollbar-thumb-hover:");
    expect(css).toContain("scrollbar-color: var(--scrollbar-thumb) var(--scrollbar-track)");
    expect(css).toContain("scrollbar-gutter: stable;");
    expect(css).toContain("overflow-x: hidden;");
    expect(css).toContain(".event-card p,");
    expect(css).toContain(".event-rationale");
    expect(css).toContain("white-space: pre-wrap;");
    expect(css).toContain(".tool-payload-block");
    expect(css).toContain("overflow-wrap: anywhere;");
  });
});

describe("theme conversation navigator contracts", () => {
  it("keeps the desktop turn rail in the same bounded grid row as the transcript", () => {
    expectRule(".detail-body", [
      "grid-template-columns: 52px minmax(0, 1fr);",
      "grid-template-rows: minmax(0, 1fr);",
    ]);
    expectRule(".timeline-pane", ["grid-row: 1;", "grid-column: 2;"]);
    expectRule(".conversation-navigator", ["grid-row: 1;", "grid-column: 1;"]);
  });
});

describe("theme session lineage contracts", () => {
  it("keeps a compact dock in flow while the animated lineage lens floats above the transcript", () => {
    expectRule(".session-lineage", [
      "position: relative;",
      "flex: none;",
    ]);
    expectRule(".lineage-dock", ["min-height: 46px;"]);
    expectRule(".lineage-lens", [
      "position: absolute;",
      "pointer-events: none;",
      "transform: translateY(-10px) scale(0.965, 0.18);",
      "transform-origin: right top;",
    ]);
    expectRule(".lineage-lens--open", [
      "pointer-events: auto;",
      "transform: translateY(0) scale(1);",
    ]);
    expectRule(".lineage-lens .dag-stage", [
      "height: clamp(200px, 32vh, 240px);",
      "min-height: 0;",
    ]);
    expectRule(".dag-stage", ["overflow: auto;"]);
  });
});

describe("theme route overflow contracts", () => {
  it("keeps search results and recall cards from widening the document", () => {
    expectRule(".page--search", ["min-width: 0;", "max-width: 100%;", "overflow-x: hidden;"]);
    expectRule(".activity-workspace > .page--search", ["min-width: 0;", "max-width: 100%;", "overflow-x: hidden;"]);
    expectRule(".result-group", ["min-width: 0;", "max-width: 100%;"]);
    expectRule(".result-row", ["min-width: 0;", "max-width: 100%;"]);
    expectRule(".result-row-body", ["min-width: 0;", "max-width: 100%;"]);
    expectRule(".recall-results", ["min-width: 0;", "max-width: 100%;", "overflow-x: hidden;"]);
    expectRule(".recall-card", ["min-width: 0;", "max-width: 100%;", "overflow-wrap: anywhere;"]);
    expectRule(".recall-rationale,\n.recall-next", ["overflow-wrap: anywhere;", "word-break: break-word;"]);
  });
});
