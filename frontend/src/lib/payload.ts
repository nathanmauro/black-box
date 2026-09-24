export function parsePayload(raw: string | null | undefined): unknown | null {
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
  const value = parsePayload(raw);
  if (typeof value !== "string") return value;
  const match = /^Exit code:\s*([^\n]+)\nWall time:\s*([^\n]+)\nOutput:\s*\n?([\s\S]*)$/u.exec(
    value.trim(),
  );
  if (!match) return value;
  return {
    exit_code: numericOrText(match[1].trim()),
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

function numericOrText(value: string): number | string {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : value;
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
