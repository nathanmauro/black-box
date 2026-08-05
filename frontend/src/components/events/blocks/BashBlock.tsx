import { Show } from "solid-js";
import { truncatePath } from "../../../lib/format";
import LazyDetails from "./LazyDetails";

type BashBlockProps = {
  block: { command: string; cwd: string | null; output: string | null; exitCode: number | null; wallTime: string | null };
};

export default function BashBlock(props: BashBlockProps) {
  const failed = () => props.block.exitCode !== null && props.block.exitCode !== 0;
  return (
    <div class="bash-block">
      <pre class="bash-command">{props.block.command}</pre>
      <div class="bash-meta">
        <Show when={props.block.cwd}>
          {(cwd) => <span class="bash-cwd" title={cwd()}>{truncatePath(cwd())}</span>}
        </Show>
        <Show when={props.block.exitCode !== null}>
          <span classList={{ "bash-exit": true, "bash-exit--error": failed() }}>exit {props.block.exitCode}</span>
        </Show>
        <Show when={props.block.wallTime}>{(wall) => <span class="bash-wall">{wall()}</span>}</Show>
      </div>
      <Show when={props.block.output}>
        {(output) => (
          <LazyDetails summary={`Output (${output().length.toLocaleString("en-US")} chars)`} class="detail-block detail-block--output">
            <pre class="detail-pre">{output()}</pre>
          </LazyDetails>
        )}
      </Show>
    </div>
  );
}
