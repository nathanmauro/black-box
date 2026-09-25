import { looksLikeJson, parseJsonObject } from "../../lib/payload";
export { looksLikeJson, parseJsonObject };

export function parseMetadata(metadata: unknown): Record<string, unknown> {
  if (!metadata) return {};
  if (typeof metadata === "string") return parseJsonObject(metadata) || {};
  if (typeof metadata === "object" && !Array.isArray(metadata))
    return metadata as Record<string, unknown>;
  return {};
}

export function metadataText(value: unknown): string {
  if (typeof value === "string") return value;
  if (value == null) return "";
  return String(value);
}

export function metadataList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => (typeof item === "string" ? item : JSON.stringify(item)))
    .filter(Boolean);
}
