import { describe, expect, it } from "vitest";

// Vitest runs this contract in Node, while the shipped frontend intentionally
// does not include Node globals in its TypeScript surface.
// @ts-expect-error Node built-ins are available in Vitest.
const { readFileSync } = await import("node:fs");

const css = readFileSync("src/theme.css", "utf8") as string;

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
    expect(css).toContain(".payload-details pre");
    expect(css).toContain("overflow-wrap: anywhere;");
  });
});
