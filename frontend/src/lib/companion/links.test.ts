import { describe, expect, it } from "vitest";
import { eventHref, recallHref } from "./links";

describe("recallHref", () => {
  it("scopes to the project and asks RecallPage to run once", () => {
    expect(recallHref("why sqlite", "/repo/a")).toBe(
      "/recall?project=%2Frepo%2Fa&query=why+sqlite&run=1",
    );
  });

  it("omits the project for an all-projects recall", () => {
    expect(recallHref("why sqlite", null)).toBe("/recall?query=why+sqlite&run=1");
  });

  it("trims the question and round-trips +, &, #, % and Unicode", () => {
    const question = "C++ & Rust #42 100% — café ✓";
    const href = recallHref(`  ${question}  `, "/repo/a b&c");
    expect(href).not.toContain("#");
    const params = new URL(href, "http://127.0.0.1").searchParams;
    expect([...params.keys()]).toEqual(["project", "query", "run"]);
    expect(params.get("project")).toBe("/repo/a b&c");
    expect(params.get("query")).toBe(question);
    expect(params.get("run")).toBe("1");
  });
});

describe("eventHref", () => {
  it("omits the project scope when there is none", () => {
    expect(eventHref("s1", "e1", null)).toBe("/?view=browse&session=s1&event=e1");
  });
});
