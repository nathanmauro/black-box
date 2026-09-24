import { describe, expect, it, vi } from "vitest";
import { MODE_SIZES, modeMessage, postToShell } from "./bridge";

describe("postToShell", () => {
  it("returns false when no shell handler exists", () => {
    expect(postToShell({ type: "state", pulse: "live", unseen: 1 }, {})).toBe(false);
    expect(postToShell({ type: "state", pulse: "live", unseen: 1 }, undefined)).toBe(false);
  });

  it("posts to window.webkit.messageHandlers.companion when present", () => {
    const postMessage = vi.fn();
    const target = { webkit: { messageHandlers: { companion: { postMessage } } } };
    expect(postToShell(modeMessage("compact"), target)).toBe(true);
    expect(postMessage).toHaveBeenCalledWith({ type: "mode", mode: "compact", width: 340, height: 420 });
  });

  it("swallows handler errors", () => {
    const target = { webkit: { messageHandlers: { companion: { postMessage: () => { throw new Error("boom"); } } } } };
    expect(postToShell({ type: "state", pulse: "idle", unseen: 0 }, target)).toBe(false);
  });

  it("exposes the three panel sizes", () => {
    expect(MODE_SIZES).toEqual({ mini: { width: 132, height: 36 }, compact: { width: 340, height: 420 }, expanded: { width: 400, height: 560 } });
  });
});
