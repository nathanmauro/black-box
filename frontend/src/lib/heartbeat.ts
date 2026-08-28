/**
 * Session heartbeat utilities for showing "last event X ago" and running/idle/stale status.
 */

export type SessionStatus = "running" | "idle" | "stale";

const RUNNING_THRESHOLD_MS = 10_000; // 10 seconds
const IDLE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes

/**
 * Format a relative time like "4s ago", "2m ago", "1h ago".
 */
export function formatRelativeTime(lastSeenAt: string | null | undefined): string {
  if (!lastSeenAt) return "unknown";

  const now = Date.now();
  const then = new Date(lastSeenAt).getTime();
  const deltaMs = now - then;

  if (deltaMs < 0) return "just now";

  if (deltaMs < 1000) return "just now";
  if (deltaMs < 60_000) return `${Math.floor(deltaMs / 1000)}s ago`;
  if (deltaMs < 3600_000) return `${Math.floor(deltaMs / 60_000)}m ago`;
  if (deltaMs < 86400_000) return `${Math.floor(deltaMs / 3600_000)}h ago`;
  return `${Math.floor(deltaMs / 86400_000)}d ago`;
}

/**
 * Determine session status from last_seen_at timestamp.
 */
export function getSessionStatus(lastSeenAt: string | null | undefined): SessionStatus {
  if (!lastSeenAt) return "stale";

  const now = Date.now();
  const then = new Date(lastSeenAt).getTime();
  const deltaMs = now - then;

  if (deltaMs < RUNNING_THRESHOLD_MS) return "running";
  if (deltaMs < IDLE_THRESHOLD_MS) return "idle";
  return "stale";
}
