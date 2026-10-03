import { decimalNumberRoundtrips, scanJsonIdentity } from "./jsonNumbers";

export type NumericPrecision = "preserved" | "changed" | "unchecked";
const PREVIEW_NUMBER_SCAN_LIMIT = 131_072;

type ParsedPayload = { value: unknown | null; numericPrecision: NumericPrecision };

export function parsePayload(raw: string | null | undefined): unknown | null {
  return decodePayload(raw);
}

/** Diagnostics are opt-in: existing parsing callers keep their value and cost contract. */
export function parsePayloadWithPrecision(raw: string | null | undefined): ParsedPayload {
  let numericPrecision: NumericPrecision = "preserved";
  const value = decodePayload(raw, (candidate) => {
    if (candidate.length > PREVIEW_NUMBER_SCAN_LIMIT) {
      numericPrecision = combinePrecision(numericPrecision, "unchecked");
    } else {
      const scanned = scanJsonIdentity(candidate);
      if (!scanned.safeNumbers || scanned.negativeZero) numericPrecision = "changed";
    }
  });
  return { value, numericPrecision };
}

function decodePayload(
  raw: string | null | undefined,
  onDecoded?: (candidate: string) => void,
): unknown | null {
  if (raw == null || !raw.trim()) return null;
  let value: unknown = raw;
  for (let attempt = 0; attempt < 2 && typeof value === "string"; attempt += 1) {
    const candidate = value.trim();
    if (!looksSerialized(candidate)) break;
    try {
      value = JSON.parse(candidate) as unknown;
    } catch {
      break;
    }
    onDecoded?.(candidate);
  }
  return value;
}

export function payloadText(raw: string | null | undefined): string | null {
  const value = parsePayload(raw);
  if (typeof value === "string") return value;
  if (!isRecord(value)) return null;
  for (const key of ["output", "stdout", "result", "content"]) {
    if (typeof value[key] === "string") return value[key] as string;
  }
  return null;
}

export function parseToolResult(raw: string | null | undefined): unknown | null {
  return formatToolResult(parsePayload(raw));
}

export function parseToolResultWithPrecision(raw: string | null | undefined): ParsedPayload {
  const parsed = parsePayloadWithPrecision(raw);
  let numericPrecision = parsed.numericPrecision;
  const value = formatToolResult(parsed.value, (text, numeric) => {
    const roundtrips =
      text.length > PREVIEW_NUMBER_SCAN_LIMIT ? undefined : decimalNumberRoundtrips(text);
    numericPrecision = combinePrecision(
      numericPrecision,
      Object.is(numeric, -0) || roundtrips === false
        ? "changed"
        : roundtrips === undefined
          ? "unchecked"
          : "preserved",
    );
  });
  return { value, numericPrecision };
}

function combinePrecision(current: NumericPrecision, next: NumericPrecision): NumericPrecision {
  if (current === "changed" || next === "changed") return "changed";
  if (current === "unchecked" || next === "unchecked") return "unchecked";
  return "preserved";
}

function formatToolResult(
  value: unknown | null,
  onConverted?: (text: string, numeric: number) => void,
): unknown | null {
  if (typeof value !== "string") return value;
  const match = /^Exit code:\s*([^\n]+)\nWall time:\s*([^\n]+)\nOutput:\s*\n?([\s\S]*)$/u.exec(
    value.trim(),
  );
  if (!match) return value;
  return {
    exit_code: numericOrText(match[1].trim(), onConverted),
    wall_time: match[2].trim(),
    output: match[3],
  };
}

function looksSerialized(value: string): boolean {
  return (
    (value.startsWith("{") && value.endsWith("}")) ||
    (value.startsWith("[") && value.endsWith("]")) ||
    (value.startsWith('"') && value.endsWith('"'))
  );
}

function numericOrText(
  value: string,
  onConverted?: (text: string, numeric: number) => void,
): number | string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return value;
  onConverted?.(value, parsed);
  return parsed;
}

export function looksLikeJson(value: string | null | undefined): boolean {
  if (!value) return false;
  const trimmed = value.trim();
  return (
    (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
    (trimmed.startsWith("[") && trimmed.endsWith("]"))
  );
}

export function parseJsonObject(value: string | null | undefined): Record<string, unknown> | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
