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

export function modeMessage(mode: CompanionMode): ShellMessage {
  return { type: "mode", mode, ...MODE_SIZES[mode] };
}
