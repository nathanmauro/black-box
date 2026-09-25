import { For } from "solid-js";
import type { InlineSpan } from "../../lib/presenters/types";
import FileReferenceActions from "./FileReferenceActions";

/**
 * Renders presenter headline spans inside the EXPANDED event card only —
 * the collapsed StreamRow is itself a <button>, so interactive spans must
 * never render there (nested buttons are invalid HTML).
 * File actions resolve reactively after the verified project catalog loads.
 */
export default function InlineSpans(props: { spans: InlineSpan[] }) {
  return (
    <For each={props.spans}>
      {(span) => {
        if (span.kind === "code") return <code class="inline-span-code">{span.text}</code>;
        if (span.kind === "fileLink") {
          return <FileReferenceActions file={span.file} label={span.label} />;
        }
        if (span.kind === "url") {
          return (
            <a
              class="inline-url"
              href={span.href}
              target="_blank"
              rel="noreferrer"
              onClick={(clickEvent) => clickEvent.stopPropagation()}
            >
              {span.label}
            </a>
          );
        }
        return <span>{span.text}</span>;
      }}
    </For>
  );
}
