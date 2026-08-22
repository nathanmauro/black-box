import { parsePayload } from "../payload";

/**
 * Shared failure adapter for presenters (spec §4.3 D3): failure-ness derives
 * from the tool payload, never from event_type. Conservative on purpose —
 * unknown shapes and parse failures are NOT errors (fail quiet), so this can
 * run on every collapsed row without inventing false alarms.
 */
export function outputLooksFailed(raw: string | null | undefined): boolean {
  try {
    if (!raw || !raw.trim()) return false;
    const value = parsePayload(raw);
    if (!isRecord(value)) return false;
    if (value.is_error === true || value.isError === true) return true;
    const exit = value.exit_code ?? value.exitCode;
    if (typeof exit === "number" && exit !== 0) return true;
    if (typeof exit === "string" && exit.trim() !== "" && Number.isFinite(Number(exit)) && Number(exit) !== 0) return true;
    if (typeof value.error === "string" && value.error.trim()) return true;
    if (value.status === "error" || value.status === "failed") return true;
    return false;
  } catch {
    return false;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
