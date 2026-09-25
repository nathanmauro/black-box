import { For, Show } from "solid-js";
import { parsePayload, parseToolResult } from "../../lib/payload";

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

export default function ToolPayload(props: ToolPayloadProps) {
  const input = () => parsePayload(props.inputJson);
  const output = () => parseToolResult(props.outputJson);

  return (
    <Show when={input() !== null || output() !== null}>
      <div class="tool-payload" aria-label={`${props.toolName || "Tool"} payload`}>
        <Show when={input() !== null}>
          <PayloadSection label="Input" value={input()} priority={INPUT_PRIORITY} />
        </Show>
        <Show when={output() !== null}>
          <PayloadSection label="Result" value={output()} priority={OUTPUT_PRIORITY} />
        </Show>
      </div>
    </Show>
  );
}

function PayloadSection(props: { label: string; value: unknown; priority: string[] }) {
  const entries = () => orderedEntries(props.value, props.priority);

  return (
    <section class="tool-payload-section" aria-label={props.label}>
      <h4>{props.label}</h4>
      <Show when={entries()} fallback={<PayloadValue value={props.value} standalone />}>
        {(items) => (
          <div class="tool-payload-fields">
            <For each={items()}>{(entry) => <PayloadField entry={entry} />}</For>
          </div>
        )}
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
