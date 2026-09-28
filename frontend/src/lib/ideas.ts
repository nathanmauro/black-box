import type { IdeaOrigin, IdeaStatus } from "./api";

export const IDEA_ORIGINS: IdeaOrigin[] = ["agent-proposed", "human-aside", "joint"];

export const IDEA_STATUSES: IdeaStatus[] = [
  "untouched",
  "partially-built",
  "built-unused",
  "superseded",
  "tracked",
];

export const LEGS_MAX = 10;

/** The legacy origin spelling the backend normalizes; the UI mirrors that for old metadata. */
export function normalizeOrigin(value: unknown): string {
  const origin = String(value ?? "")
    .trim()
    .toLowerCase();
  return origin === "nathan-aside" ? "human-aside" : origin;
}

export function isKnownOrigin(value: string | null | undefined): value is IdeaOrigin {
  return IDEA_ORIGINS.includes(normalizeOrigin(value) as IdeaOrigin);
}

export function isKnownStatus(value: string | null | undefined): value is IdeaStatus {
  return IDEA_STATUSES.includes(String(value ?? "").trim() as IdeaStatus);
}

/** Comma-separated (or repeated) status query values, restricted to known statuses in canonical order. */
export function parseStatusParam(value: string | string[] | null | undefined): IdeaStatus[] {
  const raw = (Array.isArray(value) ? value : [value ?? ""])
    .flatMap((part) => part.split(","))
    .map((part) => part.trim());
  return IDEA_STATUSES.filter((status) => raw.includes(status));
}

export function parseOriginParam(value: string | string[] | null | undefined): IdeaOrigin | null {
  const first = Array.isArray(value) ? value[0] : value;
  const origin = normalizeOrigin(first);
  return isKnownOrigin(origin) ? (origin as IdeaOrigin) : null;
}

export function clampLegs(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const legs = Number(value);
  if (!Number.isFinite(legs)) return null;
  return Math.max(0, Math.min(LEGS_MAX, Math.round(legs)));
}

/** Human label for a status or origin value: "partially-built" -> "partially built". */
export function ideaValueLabel(value: string): string {
  return value.replaceAll("-", " ");
}

/** The ideas nobody answered: proposed by an agent and never picked up. */
export function isUnanswered(idea: { origin?: string | null; status?: string | null }): boolean {
  return (
    normalizeOrigin(idea.origin) === "agent-proposed" &&
    (idea.status || "untouched") === "untouched"
  );
}

export type SafeLink = { href: string; external: boolean };

/**
 * A captured link is untrusted text. Only web and Obsidian app links become anchors; web links open
 * in a new tab without an opener, and obsidian:// stays in place so the OS hands it to the app.
 */
export function safeIdeaLink(value: string | null | undefined): SafeLink | null {
  const raw = value?.trim();
  if (!raw) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol === "http:" || url.protocol === "https:")
    return { href: url.href, external: true };
  if (url.protocol === "obsidian:") return { href: raw, external: false };
  return null;
}

export type IdeaFields = {
  title: string;
  oneLiner: string;
  origin: string;
  status: string;
  legs: number | null;
  quote: string;
  connects: string[];
  resumeStep: string;
  link: string;
};

const IDEA_TEXT_PREFIX = /^\[Idea\]\s*/i;

/**
 * Idea fields from an event's metadata, falling back to the event text's first line
 * ("[Idea] <title> — <oneLiner>") when metadata is missing or partial.
 */
export function ideaFieldsFromEvent(
  metadata: Record<string, unknown>,
  text: string | null | undefined,
): IdeaFields {
  const firstLine = (text ?? "").split(/\r?\n/).find((line) => line.trim()) ?? "";
  const stripped = firstLine.replace(IDEA_TEXT_PREFIX, "").trim();
  const [textTitle, ...rest] = stripped.split(" — ");
  const str = (value: unknown) => (typeof value === "string" ? value.trim() : "");
  const connects = Array.isArray(metadata.connects)
    ? metadata.connects.map((item) => String(item).trim()).filter(Boolean)
    : [];
  return {
    title: str(metadata.title) || textTitle?.trim() || "Idea",
    oneLiner: str(metadata.oneLiner) || rest.join(" — ").trim(),
    origin: normalizeOrigin(metadata.origin),
    status: str(metadata.status) || "untouched",
    legs: clampLegs(metadata.legs),
    quote: typeof metadata.quote === "string" ? metadata.quote : "",
    connects,
    resumeStep: str(metadata.resumeStep),
    link: str(metadata.link),
  };
}

/** "title — oneLiner", the single-line headline stream rows and palette entries use. */
export function ideaHeadline(fields: { title: string; oneLiner: string }): string {
  return fields.oneLiner ? `${fields.title} — ${fields.oneLiner}` : fields.title;
}
