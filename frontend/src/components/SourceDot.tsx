import { sourceColor, sourceLabel } from "../lib/format";

type SourceDotProps = {
  source?: string | null;
  label?: boolean;
};

export default function SourceDot(props: SourceDotProps) {
  return (
    <span class="source-dot-wrap" title={sourceLabel(props.source)}>
      <span class="source-dot" style={{ "--source-color": sourceColor(props.source) }} aria-hidden="true" />
      {props.label ? <span>{sourceLabel(props.source)}</span> : null}
    </span>
  );
}
