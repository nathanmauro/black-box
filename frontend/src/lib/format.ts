const SOURCE_COLORS: Record<string, string> = {
  claude: "#7c5cfc",
  codex: "#34d399",
  cursor: "#60a5fa",
  raycast: "#f59e0b",
  cockpit: "#e879f9",
  cli: "#9ca3af",
  manual: "#6b7280",
};

export const SOURCES = ["claude", "codex", "cursor", "raycast", "cockpit", "cli", "manual"] as const;

export type SourceName = (typeof SOURCES)[number];

export function timeAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "now";
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return "now";
  const seconds = Math.max(0, Math.floor((now - then) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months}mo`;
  return `${Math.floor(months / 12)}y`;
}

export function truncatePath(cwd: string | null | undefined): string {
  if (!cwd) return "unknown project";
  return cwd.replace(/^\/Users\/[^/]+(?=\/|$)/, "~");
}

export function sourceColor(source: string | null | undefined): string {
  const key = String(source || "").toLowerCase();
  return SOURCE_COLORS[key] || "#6b7280";
}

export function sourceLabel(source: string | null | undefined): string {
  const key = String(source || "unknown").trim();
  if (!key) return "Unknown";
  if (key.toLowerCase() === "cli") return "CLI";
  return key.charAt(0).toUpperCase() + key.slice(1).toLowerCase();
}
