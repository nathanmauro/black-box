/**
 * Follow mode persistence (pin-to-newest toggle).
 */

const STORAGE_KEY = "streamFollowMode";

export function loadFollowMode(): boolean {
  if (typeof localStorage === "undefined") return false;
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === "true";
}

export function saveFollowMode(enabled: boolean): void {
  if (typeof localStorage === "undefined") return;
  localStorage.setItem(STORAGE_KEY, String(enabled));
}
