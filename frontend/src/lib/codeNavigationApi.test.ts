import { afterEach, describe, expect, it, vi } from "vitest";
import { getCodeProjectScopes, openInEditor, revealInFinder } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("code navigation API", () => {
  it("loads eligible scopes and posts relative references without an absolute target", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([{ projectKey: "key", root: "/repo" }]), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: "opened" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: "revealed" }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getCodeProjectScopes()).resolves.toEqual([{ projectKey: "key", root: "/repo" }]);
    const reference = { projectKey: "key", relativePath: "src/App.ts", line: 3 };
    await expect(openInEditor(reference)).resolves.toEqual({ status: "opened" });
    await expect(revealInFinder(reference)).resolves.toEqual({ status: "revealed" });

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/projects/code-scopes", expect.anything());
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/open-in-editor", expect.objectContaining({
      method: "POST",
      body: JSON.stringify(reference),
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(3, "/api/reveal-in-finder", expect.objectContaining({
      method: "POST",
      body: JSON.stringify(reference),
    }));
    expect(String(fetchMock.mock.calls[1]?.[1]?.body)).not.toContain("/repo");
  });
});
