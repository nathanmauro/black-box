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
    expect(postMessage).toHaveBeenCalledWith({
      type: "mode",
      mode: "compact",
      width: 340,
      height: 420,
    });
  });

  it("swallows handler errors", () => {
    const target = {
      webkit: {
        messageHandlers: {
          companion: {
            postMessage: () => {
              throw new Error("boom");
            },
          },
        },
      },
    };
    expect(postToShell({ type: "state", pulse: "idle", unseen: 0 }, target)).toBe(false);
  });

  it("exposes the three panel sizes", () => {
    expect(MODE_SIZES).toEqual({
      mini: { width: 132, height: 36 },
      compact: { width: 340, height: 420 },
      expanded: { width: 400, height: 560 },
    });
  });
});

describe("modeMessage", () => {
  it("uses the default mini width when no measurement is given", () => {
    expect(modeMessage("mini")).toEqual({ type: "mode", mode: "mini", width: 132, height: 36 });
  });

  it("grows the mini width to fit a wider measured chip, so a long pulse label and a two-digit count are never clipped", () => {
    expect(modeMessage("mini", 168)).toEqual({
      type: "mode",
      mode: "mini",
      width: 168,
      height: 36,
    });
  });

  it("never shrinks the mini chip below its default width", () => {
    expect(modeMessage("mini", 90)).toEqual({ type: "mode", mode: "mini", width: 132, height: 36 });
  });

  it("ignores a non-finite or non-positive measurement", () => {
    expect(modeMessage("mini", Number.NaN)).toEqual({
      type: "mode",
      mode: "mini",
      width: 132,
      height: 36,
    });
    expect(modeMessage("mini", Number.POSITIVE_INFINITY)).toEqual({
      type: "mode",
      mode: "mini",
      width: 132,
      height: 36,
    });
    expect(modeMessage("mini", -10)).toEqual({
      type: "mode",
      mode: "mini",
      width: 132,
      height: 36,
    });
    expect(modeMessage("mini", 0)).toEqual({ type: "mode", mode: "mini", width: 132, height: 36 });
  });

  it("ignores the measurement outside mini mode", () => {
    expect(modeMessage("compact", 999)).toEqual({
      type: "mode",
      mode: "compact",
      width: 340,
      height: 420,
    });
  });
});
