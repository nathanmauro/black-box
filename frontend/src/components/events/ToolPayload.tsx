import { createMemo, For, Show } from "solid-js";
import { parsePayloadWithPrecision, parseToolResultWithPrecision } from "../../lib/payload";
import { compactPayloadMedia } from "../../lib/payloadPreview";
import LazyDetails from "./blocks/LazyDetails";

export { parsePayload, payloadText } from "../../lib/payload";

type ToolPayloadProps = {
  toolName?: string | null;
  inputJson?: string | null;
  outputJson?: string | null;
};

type PayloadEntry = {
  key: string;
  value: unknown;
};

const INPUT_PRIORITY = [
  "command",
  "cmd",
  "script",
  "cwd",
  "workdir",
  "file_path",
  "filePath",
  "path",
  "url",
  "query",
  "pattern",
  "prompt",
  "question",
  "input",
  "content",
];

const OUTPUT_PRIORITY = [
  "exit_code",
  "exitCode",
  "status",
  "code",
  "wall_time_seconds",
  "wallTimeSeconds",
  "duration_ms",
  "durationMs",
  "output",
  "stdout",
  "stderr",
  "error",
  "result",
  "content",
];

const LARGE_PAYLOAD_CHARS = 1200;

export default function ToolPayload(props: ToolPayloadProps) {
  return (
    <Show when={props.inputJson?.trim() || props.outputJson?.trim()}>
      <div class="tool-payload" aria-label={`${props.toolName || "Tool"} payload`}>
        <Show when={props.inputJson?.trim()}>
          <PayloadSection label="Input" raw={props.inputJson!} priority={INPUT_PRIORITY} />
        </Show>
        <Show when={props.outputJson?.trim()}>
          <PayloadSection label="Result" raw={props.outputJson!} priority={OUTPUT_PRIORITY} />
        </Show>
      </div>
    </Show>
  );
}

type PayloadSectionProps = { label: string; raw: string; priority: string[] };

function PayloadSection(props: PayloadSectionProps) {
  return (
    <Show
      when={props.raw.length > LARGE_PAYLOAD_CHARS}
      fallback={<PayloadSectionBody {...props} />}
    >
      <LazyDetails summary={`${props.label} (${props.raw.length.toLocaleString("en-US")} chars)`}>
        <PayloadSectionBody {...props} />
      </LazyDetails>
    </Show>
  );
}

function PayloadSectionBody(props: PayloadSectionProps) {
  const preview = createMemo(() => {
    const parsed =
      props.label === "Result"
        ? parseToolResultWithPrecision(props.raw)
        : parsePayloadWithPrecision(props.raw);
    return { ...compactPayloadMedia(parsed.value), numericPrecision: parsed.numericPrecision };
  });
  const entries = () => orderedEntries(preview().value, props.priority);

  return (
    <section class="tool-payload-section" aria-label={props.label}>
      <h4>{props.label}</h4>
      <Show when={entries()} fallback={<PayloadValue value={preview().value} standalone />}>
        {(items) => (
          <div class="tool-payload-fields">
            <For each={items()}>{(entry) => <PayloadField entry={entry} />}</For>
          </div>
        )}
      </Show>
      <Show when={preview().compacted}>
        <p class="tool-payload-note">Embedded media is shortened in this preview.</p>
      </Show>
      <Show when={preview().depthLimited}>
        <p class="tool-payload-note">Deeply nested content is shortened in this preview.</p>
      </Show>
      <Show when={preview().numericPrecision === "changed"}>
        <p class="tool-payload-note">
          Numeric values changed in this preview. Open Original for exact captured text.
        </p>
      </Show>
      <Show when={preview().numericPrecision === "unchecked"}>
        <p class="tool-payload-note">
          Numeric precision could not be checked in this preview. Open Original for exact captured
          text.
        </p>
      </Show>
      <Show
        when={
          preview().compacted ||
          preview().depthLimited ||
          props.raw.length > LARGE_PAYLOAD_CHARS ||
          preview().numericPrecision !== "preserved"
        }
      >
        <LazyDetails
          summary={`Original ${props.label.toLowerCase()} (${props.raw.length.toLocaleString("en-US")} chars)`}
        >
          <pre class="tool-payload-block tool-payload-original">{props.raw}</pre>
        </LazyDetails>
      </Show>
    </section>
  );
}

function PayloadField(props: { entry: PayloadEntry }) {
  return (
    <div
      classList={{
        "tool-payload-field": true,
        "tool-payload-field--metric": isMetricKey(props.entry.key),
      }}
    >
      <span class="tool-payload-label">{fieldLabel(props.entry.key)}</span>
      <PayloadValue value={props.entry.value} />
    </div>
  );
}

function PayloadValue(props: { value: unknown; standalone?: boolean }) {
  const text = () => scalarText(props.value);
  const block = () =>
    typeof props.value === "string" && (props.value.includes("\n") || props.value.length > 120);
  const structured = () => props.value !== null && typeof props.value === "object";

  return (
    <Show
      when={!structured()}
      fallback={
        <pre class="tool-payload-block tool-payload-block--json">
          {formatStructured(props.value)}
        </pre>
      }
    >
      <Show
        when={block() || props.standalone}
        fallback={<code class="tool-payload-inline">{text()}</code>}
      >
        <pre class="tool-payload-block">{text()}</pre>
      </Show>
    </Show>
  );
}

function orderedEntries(value: unknown, priority: string[]): PayloadEntry[] | null {
  if (!isRecord(value)) return null;
  const rank = new Map(priority.map((key, index) => [key, index]));
  return Object.entries(value)
    .map(([key, entryValue]) => ({ key, value: entryValue }))
    .sort((left, right) => {
      const leftRank = rank.get(left.key) ?? priority.length;
      const rightRank = rank.get(right.key) ?? priority.length;
      return leftRank - rightRank;
    });
}

function scalarText(value: unknown): string {
  if (value == null) return "—";
  if (typeof value === "string") return value;
  return String(value);
}

function formatStructured(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function fieldLabel(key: string): string {
  const spaced = key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .trim();
  if (!spaced) return "Value";
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function isMetricKey(key: string): boolean {
  return /(?:^|_)(?:exit_?)?code$|status|duration|wall_?time/i.test(key);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
