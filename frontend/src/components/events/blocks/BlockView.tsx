import { createMemo, For, Show, type JSX } from "solid-js";
import { parseApplyPatch } from "../../../lib/patch";
import type { DetailBlock } from "../../../lib/presenters/types";
import type { PatchFileStub } from "../../../lib/patch";
import ToolPayload from "../ToolPayload";
import FileReferenceActions from "../FileReferenceActions";
import BashBlock from "./BashBlock";
import DiffBlock, { DiffHunks } from "./DiffBlock";
import LazyDetails from "./LazyDetails";

/**
 * A block value is immutable for a given event, so a plain switch (not <Switch>)
 * is safe: no prop path here is reactive.
 */
export default function BlockView(props: {
  block: DetailBlock;
  eventId: string;
  index: number;
}): JSX.Element {
  const block = props.block;
  switch (block.kind) {
    case "bash":
      return <BashBlock block={block} />;
    case "diff":
      return (
        <DiffBlock
          eventId={props.eventId}
          index={props.index}
          file={block.file}
          oldText={block.oldText}
          newText={block.newText}
          label={block.label}
        />
      );
    case "patch":
      return <PatchBlock command={block.command} files={block.files} />;
    case "code":
      return (
        <LazyDetails summary={block.label} class="detail-block detail-block--code">
          <pre class="detail-pre">{block.text}</pre>
        </LazyDetails>
      );
    case "markdown": // rendered as plain text until slice 5
      return <pre class="detail-pre detail-pre--inline">{block.text}</pre>;
    case "text":
      return (
        <LazyDetails summary={block.label} class="detail-block">
          <pre class="detail-pre">{block.text}</pre>
        </LazyDetails>
      );
    case "json":
    case "plan": // JSON view until slice 5 ships the plan component
      return (
        <LazyDetails
          summary={block.kind === "json" ? block.label : "Plan"}
          class="detail-block detail-block--json"
        >
          <pre class="detail-pre">{safeStringify(block.kind === "json" ? block.value : block)}</pre>
        </LazyDetails>
      );
    case "fallback":
      return (
        <ToolPayload
          toolName={block.toolName}
          inputJson={block.inputJson}
          outputJson={block.outputJson}
        />
      );
  }
}

function PatchBlock(props: { command: string; files: PatchFileStub[] }) {
  const summary = () =>
    `Patch — ${props.files.length || "?"} file${props.files.length === 1 ? "" : "s"} (${props.command.length.toLocaleString("en-US")} chars)`;
  return (
    <LazyDetails summary={summary()} class="detail-block detail-block--diff">
      <PatchBody command={props.command} />
    </LazyDetails>
  );
}

function PatchBody(props: { command: string }) {
  const parsed = createMemo(() => parseApplyPatch(props.command));
  return (
    <Show when={parsed()} fallback={<pre class="detail-pre">{props.command}</pre>}>
      {(files) => (
        <For each={files()}>
          {(file) => (
            <div class="patch-file">
              <div class="patch-file-head">
                <span class={`patch-op patch-op--${file.op}`}>{file.op}</span>
                <FileReferenceActions file={{ path: file.path }} label={file.path} />
                <Show when={file.movedTo}>
                  {(target) => (
                    <span class="patch-move">
                      → <FileReferenceActions file={{ path: target() }} label={target()} />
                    </span>
                  )}
                </Show>
              </div>
              <DiffHunks hunks={file.hunks} />
            </div>
          )}
        </For>
      )}
    </Show>
  );
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
