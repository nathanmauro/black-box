import type { ContinuationCandidate } from "./api";

export const CONTINUATION_SOURCES_HEADING = "\n## Sources\n";

/**
 * The prompt to copy: only the candidate's exact `evidence[].href` strings become absolute, and only
 * on their own Sources lines. The real Sources heading is always the last one, because fenced
 * captured text can only appear before it.
 */
export function promptWithAbsoluteLinks(
  candidate: Pick<ContinuationCandidate, "prompt" | "evidence">,
  origin: string,
): string {
  const start = candidate.prompt.lastIndexOf(CONTINUATION_SOURCES_HEADING);
  if (start < 0) return candidate.prompt;
  const hrefs = [...new Set(candidate.evidence.map((item) => item.href))]
    .filter((href) => href.startsWith("/") && !href.startsWith("//"))
    .sort((left, right) => right.length - left.length);
  const sources = candidate.prompt
    .slice(start)
    .split("\n")
    .map((line) => {
      const href = hrefs.find((value) => line.endsWith(` ${value}`));
      return href
        ? `${line.slice(0, line.length - href.length)}${new URL(href, origin).href}`
        : line;
    })
    .join("\n");
  return candidate.prompt.slice(0, start) + sources;
}

export function continuationBasisLabel(basis: string): string {
  return basis === "handoff.nextAction" ? "Recorded next step" : "Open loop";
}

export function recordedAgo(days: number): string {
  if (!Number.isFinite(days) || days <= 0) return "recorded less than a day ago";
  return days === 1 ? "recorded 1 day ago" : `recorded ${days} days ago`;
}
