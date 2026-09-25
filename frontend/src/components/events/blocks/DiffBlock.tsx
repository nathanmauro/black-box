import { createMemo, For, Show } from "solid-js";
import { memoizedDiffLines, type Hunk } from "../../../lib/diff";
import type { FileRef } from "../../../lib/presenters/types";
import FileReferenceActions from "../FileReferenceActions";
import LazyDetails from "./LazyDetails";

type DiffBlockProps = {
  eventId: string;
  index: number;
  file: FileRef;
  oldText: string;
  newText: string;
  label: string;
};

export default function DiffBlock(props: DiffBlockProps) {
  return (
    <LazyDetails summary={props.label} class="detail-block detail-block--diff">
      <div class="diff-file-reference">
        <FileReferenceActions file={props.file} />
      </div>
      <DiffBody
        eventId={props.eventId}
        index={props.index}
        oldText={props.oldText}
        newText={props.newText}
      />
    </LazyDetails>
  );
}

function DiffBody(props: { eventId: string; index: number; oldText: string; newText: string }) {
  const hunks = createMemo(() =>
    memoizedDiffLines(`${props.eventId}:${props.index}`, props.oldText, props.newText),
  );
  return <DiffHunks hunks={hunks()} />;
}

export function DiffHunks(props: { hunks: Hunk[] }) {
  return (
    <div class="diff">
      <Show when={props.hunks.length} fallback={<p class="diff-empty">No line changes.</p>}>
        <For each={props.hunks}>
          {(hunk, hunkIndex) => (
            <>
              <Show when={hunkIndex() > 0}>
                <div class="diff-hunk-sep" aria-hidden="true" />
              </Show>
              <For each={hunk.lines}>
                {(line) => (
                  <div
                    classList={{
                      "diff-line": true,
                      "diff-line--add": line.kind === "add",
                      "diff-line--del": line.kind === "del",
                    }}
                  >
                    <span class="diff-gutter">
                      {line.kind === "add" ? "+" : line.kind === "del" ? "−" : " "}
                    </span>
                    <span class="diff-text">{line.text}</span>
                  </div>
                )}
              </For>
            </>
          )}
        </For>
      </Show>
    </div>
  );
}
