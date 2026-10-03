/** Display-only media compaction. Canonical payload strings are never changed. */
export function compactPayloadMedia(value: unknown): {
  value: unknown;
  compacted: boolean;
  depthLimited: boolean;
} {
  let compacted = false;
  let depthLimited = false;
  const placeholder = (mime: string, count: number, encoding: string) => {
    compacted = true;
    return `[Embedded ${mime}; ${count.toLocaleString("en-US")} ${encoding} chars]`;
  };
  const visit = (item: unknown, depth = 0): unknown => {
    if (typeof item === "string") {
      // Stop at JSON/string delimiters, including escaped quotes in nested tool JSON.
      return item.replace(
        /data:([a-z0-9.+-]+\/[a-z0-9.+-]+)(;[^,\s"'<>\\]*)?,([^\s"'<>\\]+)/giu,
        (match, mime: string, parameters: string | undefined, data: string) => {
          if (!/;base64(?:;|$)/iu.test(parameters ?? "")) {
            return placeholder(mime, data.length, "encoded");
          }
          // A URL inside Markdown or prose can end before the next whitespace/quote.
          // Preserve punctuation and readable text following its base64 alphabet.
          const encoded = /^[a-z0-9+/]+={0,2}/iu.exec(data)?.[0];
          return encoded
            ? placeholder(mime, encoded.length, "base64") + data.slice(encoded.length)
            : match;
        },
      );
    }
    if (!item || typeof item !== "object") return item;
    if (depth >= 64) {
      depthLimited = true;
      return "[Nested content omitted after 64 levels; open Original for full evidence]";
    }
    if (Array.isArray(item)) return item.map((entry) => visit(entry, depth + 1));
    const record = item as Record<string, unknown>;
    const mime = record.mimeType ?? record.media_type;
    const typed = typeof mime === "string" && /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/iu.test(mime);
    const base64 =
      typed &&
      (record.type === "image" ||
        record.type === "audio" ||
        record.type === "base64" ||
        record.encoding === "base64");
    return Object.fromEntries(
      Object.entries(record).map(([key, entry]) => [
        key,
        typeof entry === "string" && typed && ((key === "data" && base64) || key === "blob")
          ? placeholder(mime, entry.length, "base64")
          : visit(entry, depth + 1),
      ]),
    );
  };
  const preview = visit(value);
  return { value: preview, compacted, depthLimited };
}

/** Bound the actual DOM text, rather than visually hiding an arbitrarily large message. */
export function readerTextPreview(
  text: string,
  limits: { chars: number; nonemptyLines: number } = { chars: 900, nonemptyLines: 10 },
): { text: string; truncated: boolean } {
  let end = Math.min(text.length, limits.chars);
  const prefix = text.slice(0, end);
  let nonemptyLines = 0;
  let offset = 0;
  for (const line of prefix.match(/[^\n]*\n|[^\n]+$/gu) ?? []) {
    if (line.trim() && ++nonemptyLines > limits.nonemptyLines) {
      end = offset;
      break;
    }
    offset += line.length;
  }
  // Do not split a surrogate pair at the character boundary.
  if (end < text.length && /[\uD800-\uDBFF]/u.test(text[end - 1] ?? "")) end -= 1;
  return end < text.length
    ? { text: `${text.slice(0, end).trimEnd()}…`, truncated: true }
    : { text, truncated: false };
}
