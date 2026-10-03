import { describe, expect, it } from "vitest";
import { compactPayloadMedia, readerTextPreview } from "./payloadPreview";

describe("compactPayloadMedia", () => {
  it("bounds deeply nested valid JSON without modifying its exact original", () => {
    const raw = '{"item":'.repeat(2200) + '"readable"' + "}".repeat(2200);
    const original = JSON.parse(raw) as unknown;
    const preview = compactPayloadMedia(original);
    expect(preview.depthLimited).toBe(true);
    expect(preview.compacted).toBe(false);
    expect(JSON.stringify(preview.value)).toContain("Nested content omitted after 64 levels");
    expect(JSON.stringify(original)).toBe(raw);
  });
  it("compacts nested MCP images while preserving readable siblings and the original", () => {
    const original = {
      content: [
        { type: "image", image_url: { url: `data:image/jpeg;base64,${"QUJD".repeat(5000)}` } },
        { type: "text", text: "The screenshot shows the Output control." },
      ],
      isError: false,
      source: { eventId: "event-1" },
    };
    const before = JSON.stringify(original);
    expect(compactPayloadMedia(original)).toEqual({
      compacted: true,
      depthLimited: false,
      value: {
        ...original,
        content: [
          { type: "image", image_url: { url: "[Embedded image/jpeg; 20,000 base64 chars]" } },
          original.content[1],
        ],
      },
    });
    expect(JSON.stringify(original)).toBe(before);
  });
  it("recognizes typed MCP blobs and Claude sources without guessing ordinary data is binary", () => {
    const value = [
      { type: "image", mimeType: "image/png", data: "QUJD", description: "diagram" },
      { type: "image", source: { type: "base64", media_type: "image/png", data: "QUJD" } },
      {
        type: "resource",
        resource: { uri: "fixture://report", mimeType: "application/pdf", blob: "QUJD" },
      },
      { type: "text", mimeType: "text/plain", data: "keep this readable text" },
    ];
    const result = JSON.stringify(compactPayloadMedia(value).value);
    expect(result).not.toContain('"QUJD"');
    for (const text of ["keep this readable text", "fixture://report", "diagram"])
      expect(result).toContain(text);
    const ordinary = { data: "QUJD".repeat(500), url: "https://example.test/image.png" };
    expect(compactPayloadMedia(ordinary)).toEqual({
      value: ordinary,
      compacted: false,
      depthLimited: false,
    });
  });
  it("compacts data URLs in nested serialized JSON while preserving surrounding text", () => {
    const value = { text: '{"image":"data:image/png;base64,QUJD","explanation":"open the menu"}' };
    expect(compactPayloadMedia(value)).toEqual({
      compacted: true,
      depthLimited: false,
      value: {
        text: '{"image":"[Embedded image/png; 4 base64 chars]","explanation":"open the menu"}',
      },
    });
  });
  it("retains Markdown and prose surrounding a base64 URL", () => {
    expect(compactPayloadMedia("![diagram](data:image/png;base64,QUJD==),caption")).toEqual({
      value: "![diagram]([Embedded image/png; 6 base64 chars]),caption",
      compacted: true,
      depthLimited: false,
    });
  });
  it("supports encoded data URLs without interpreting media", () => {
    expect(compactPayloadMedia("before data:image/svg+xml,%3Csvg%3E after")).toEqual({
      value: "before [Embedded image/svg+xml; 9 encoded chars] after",
      compacted: true,
      depthLimited: false,
    });
  });
});

describe("readerTextPreview", () => {
  it("uses the smaller first-turn limits without changing the original", () => {
    const text = "One\r\nTwo\r\nThree\r\nFour\r\nFull tail";
    expect(readerTextPreview(text, { chars: 280, nonemptyLines: 4 })).toEqual({
      text: "One\r\nTwo\r\nThree\r\nFour…",
      truncated: true,
    });
    expect(readerTextPreview(text).text).toBe(text);
  });
  it("preserves short whitespace exactly", () => {
    const text = "  Two lines.\r\nA small message.  ";
    expect(readerTextPreview(text)).toEqual({ text, truncated: false });
  });
  it("bounds long text and marks the excerpt", () => {
    expect(readerTextPreview("a".repeat(10000))).toEqual({
      text: "a".repeat(900) + "…",
      truncated: true,
    });
  });
  it("limits nonempty lines while preserving their original line endings", () => {
    const result = readerTextPreview(
      Array.from({ length: 11 }, (_, i) => `line ${i + 1}`).join("\r\n\r\n"),
    );
    expect(result.text).toContain("line 10");
    expect(result.text).not.toContain("line 11");
    expect(result.text).toContain("line 1\r\n\r\nline 2");
    expect(result.truncated).toBe(true);
  });
  it("does not split Unicode surrogate pairs", () => {
    expect(readerTextPreview("a".repeat(899) + "🧭" + "z")).toEqual({
      text: "a".repeat(899) + "…",
      truncated: true,
    });
  });
});
