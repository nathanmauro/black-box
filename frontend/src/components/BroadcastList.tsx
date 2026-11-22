import { For, Show } from "solid-js";
import type { ProjectBroadcast, RecalledBroadcast } from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";

type BroadcastRow = RecalledBroadcast & Partial<Pick<ProjectBroadcast, "projectScope" | "stale">>;

type BroadcastListProps = {
  broadcasts: BroadcastRow[];
  /** Show each row's project; only useful when the list spans projects. */
  showProject?: boolean;
  now?: number;
};

const KIND_LABELS: Record<string, string> = {
  hazard: "Hazard",
  invariant: "Invariant",
  tentacle: "Tentacle",
};

/** The live per-project set agents publish with setBroadcast, most urgent first. */
export default function BroadcastList(props: BroadcastListProps) {
  return (
    <ul class="broadcast-list">
      <For each={props.broadcasts}>
        {(broadcast) => (
          <li
            class="broadcast-row"
            classList={{ "broadcast-row--stale": Boolean(broadcast.stale) }}
            data-kind={broadcast.kind}
            data-severity={broadcast.severity}
          >
            <div class="broadcast-row-head">
              <span class={`broadcast-kind broadcast-kind--${broadcast.kind}`}>
                {KIND_LABELS[broadcast.kind] ?? broadcast.kind}
              </span>
              <Show when={broadcast.severity !== "info"}>
                <span class={`broadcast-severity broadcast-severity--${broadcast.severity}`}>
                  {broadcast.severity}
                </span>
              </Show>
              <Show when={broadcast.stale}>
                <span class="broadcast-stale">stale</span>
              </Show>
              <code class="broadcast-key">{broadcast.key}</code>
            </div>
            <p class="broadcast-message">{broadcast.message}</p>
            <small class="broadcast-meta">
              <Show when={props.showProject && broadcast.projectScope}>
                <span title={broadcast.projectScope}>
                  {truncatePath(broadcast.projectScope ?? "")}
                </span>
                {" · "}
              </Show>
              updated {timeAgo(broadcast.updatedAt, props.now)} ago
              <Show when={broadcast.followUpAt}>
                {(followUp) => <> · {followUpLabel(followUp(), props.now)}</>}
              </Show>
              <Show when={broadcast.expiresAt}>
                {(expiresAt) => <> · {expiryLabel(expiresAt(), props.now)}</>}
              </Show>
            </small>
          </li>
        )}
      </For>
    </ul>
  );
}

function followUpLabel(iso: string, now = Date.now()): string {
  const due = Date.parse(iso);

  if (Number.isNaN(due)) {
    return "follow-up set";
  }

  return due <= now ? `follow-up due ${timeAgo(iso, now)} ago` : `follow up ${calendarDay(iso)}`;
}

function expiryLabel(iso: string, now = Date.now()): string {
  const expires = Date.parse(iso);

  if (Number.isNaN(expires)) {
    return "expires";
  }

  return expires <= now
    ? `expired ${timeAgo(iso, now)} ago`
    : `expires in ${timeUntil(expires, now)}`;
}

function timeUntil(target: number, now: number): string {
  return timeAgo(new Date(now).toISOString(), target);
}

function calendarDay(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}
