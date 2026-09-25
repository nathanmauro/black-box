import type { JSX } from "solid-js";

export default function HandoffContext(props: {
  text: string | null | undefined;
  label?: string;
  children?: JSX.Element;
}) {
  return (
    <details class="detail-block handoff-context">
      <summary>{props.label || "Read full handoff"}</summary>
      <p>{props.text}</p>
      {props.children}
    </details>
  );
}
