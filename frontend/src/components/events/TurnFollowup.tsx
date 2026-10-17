import { createResource, createSignal, For, Show } from "solid-js";
import { getTurnFollowup } from "../../lib/api";
import { timeAgo } from "../../lib/format";
import { kindMarkOf } from "../../lib/presenters/marks";
import { EventRenderer, eventHeadline, eventHeadlineSpans } from "./EventRow";

type TurnFollowupProps = {
  eventId: string;
  textExpanded?: boolean;
};

/**
 * Human-turns mode hides the agent's side of the conversation; expanding a turn shows what the
 * agent did next, up to the following human turn, without leaving the filtered feed.
 */
export default function TurnFollowup(props: TurnFollowupProps) {
  const [followup] = createResource(
    () => props.eventId,
    (id) => getTurnFollowup(id),
  );
  const [open, setOpen] = createSignal<string | null>(null);

  const toggle = (id: string) => setOpen((current) => (current === id ? null : id));

  return (
    <section class="turn-followup" aria-label="What happened next">
      <Show when={!followup.loading} fallback={<p class="turn-followup-note">Loading…</p>}>
        <Show
          when={!followup.error}
          fallback={<p class="turn-followup-note">Could not load what happened next.</p>}
        >
          <Show
            when={followup()?.events.length}
            fallback={<p class="turn-followup-note">Nothing recorded after this turn yet.</p>}
          >
            <p class="turn-followup-label">
              Then · {followup()!.events.length.toLocaleString()}
              {followup()!.complete ? "" : "+"} events
            </p>
            <ol class="turn-followup-list">
              <For each={followup()!.events}>
                {(event) => {
                  const mark = kindMarkOf(event);
                  return (
                    <li>
                      <button
                        type="button"
                        class="turn-followup-row"
                        aria-expanded={open() === event.id}
                        aria-label={eventHeadline(event)}
                        onClick={() => toggle(event.id)}
                      >
                        <span
                          classList={{ "kind-mark": true, "kind-mark--error": mark.error }}
                          aria-hidden="true"
                        >
                          {mark.label}
                        </span>
                        <span class="stream-row-headline">
                          <For each={eventHeadlineSpans(event)}>
                            {(span) =>
                              span.kind === "arg" ? (
                                <span class="headline-arg">{span.text}</span>
                              ) : (
                                span.text
                              )
                            }
                          </For>
                        </span>
                        <time dateTime={event.observedAt} title={event.observedAt}>
                          {timeAgo(event.observedAt)}
                        </time>
                      </button>
                      <Show when={open() === event.id}>
                        <div class="turn-followup-detail">
                          <EventRenderer event={event} textExpanded={props.textExpanded} />
                        </div>
                      </Show>
                    </li>
                  );
                }}
              </For>
            </ol>
            <Show when={!followup()!.complete}>
              <p class="turn-followup-note">More follows — open the session to see the rest.</p>
            </Show>
          </Show>
        </Show>
      </Show>
    </section>
  );
}
