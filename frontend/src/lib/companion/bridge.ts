import type { CompanionMode, PulseState } from "./model";

export const MODE_SIZES: Readonly<Record<CompanionMode, { width: number; height: number }>> = {
  mini: { width: 132, height: 36 },
  compact: { width: 340, height: 420 },
  expanded: { width: 400, height: 560 },
};

export type ShellMessage =
  | { type: "state"; pulse: PulseState; unseen: number }
  | { type: "mode"; mode: CompanionMode; width: number; height: number };

type ShellTarget = { webkit?: { messageHandlers?: { companion?: { postMessage(message: unknown): void } } } };

export function postToShell(message: ShellMessage, target: unknown = typeof window === "undefined" ? undefined : window): boolean {
  const handler = (target as ShellTarget | undefined)?.webkit?.messageHandlers?.companion;
  if (!handler) return false;
  try {
    handler.postMessage(message);
    return true;
  } catch {
    return false;
  }
}

/**
 * The mini chip's width is content-dependent ("connecting" plus a two-digit unseen count is wider
 * than the fixed default), so the page measures its own rendered width and passes it here. The
 * shell width is the larger of the default and that measurement, and a missing or invalid
 * measurement (not finite, zero, or negative) falls back to the default — the chip never gets
 * clipped and never shrinks below its usual size. Only mini uses this; compact and expanded keep
 * their fixed sizes.
 */
export function modeMessage(mode: CompanionMode, measuredMiniWidth?: number): ShellMessage {
  const base = MODE_SIZES[mode];
  if (mode !== "mini" || measuredMiniWidth == null || !Number.isFinite(measuredMiniWidth) || measuredMiniWidth <= 0) {
    return { type: "mode", mode, ...base };
  }
  return { type: "mode", mode, width: Math.max(base.width, Math.ceil(measuredMiniWidth)), height: base.height };
}
