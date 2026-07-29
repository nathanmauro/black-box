import { For } from "solid-js";
import type { InlineSpan } from "../../lib/presenters/types";

/**
 * Renders presenter headline spans inside the EXPANDED event card only —
 * the collapsed StreamRow is itself a <button>, so interactive spans must
 * never render there (nested buttons are invalid HTML).
 * fileLink click copies the path; slice 3 upgrades this to open-in-editor.
 */
export default function InlineSpans(props: { spans: InlineSpan[] }) {
  return (
    <For each={props.spans}>
      {(span) => {
        if (span.kind === "code") return <code class="inline-span-code">{span.text}</code>;
        if (span.kind === "fileLink") {
          return (
            <button
              type="button"
              class="inline-file-link"
              title={`${span.file.path} — click to copy path`}
              onClick={(clickEvent) => {
                clickEvent.stopPropagation();
                void navigator.clipboard?.writeText(span.file.path);
              }}
            >
              {span.label}
            </button>
          );
        }
        if (span.kind === "url") {
          return (
            <a class="inline-url" href={span.href} target="_blank" rel="noreferrer" onClick={(clickEvent) => clickEvent.stopPropagation()}>
              {span.label}
            </a>
          );
        }
        return <span>{span.text}</span>;
      }}
    </For>
  );
}
