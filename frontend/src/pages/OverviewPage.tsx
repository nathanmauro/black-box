import { A, useNavigate } from "@solidjs/router";
import { createResource, createSignal, For, Show } from "solid-js";
import KindBadge from "../components/KindBadge";
import SourceDot from "../components/SourceDot";
import { getStatus } from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import { useLiveStore } from "../lib/sse";
import { createSessionsResource } from "../lib/stores";

export default function OverviewPage() {
  const navigate = useNavigate();
  const live = useLiveStore();
  const [query, setQuery] = createSignal("");
  const [sessions] = createSessionsResource(12);
  const [status] = createResource(getStatus);
  const storage = () => status()?.storage || {};

  function submit(event: SubmitEvent) {
    event.preventDefault();
    const q = query().trim();
    navigate(q ? `/search?q=${encodeURIComponent(q)}` : "/search");
  }

  return (
    <section class="page page--overview">
      <div class="hero-search">
        <form onSubmit={submit}>
          <label for="overview-search">Search sessions and events</label>
          <div class="hero-search-row">
            <input
              id="overview-search"
              value={query()}
              onInput={(event) => setQuery(event.currentTarget.value)}
              placeholder="source:codex kind:Decision recall bug"
              autocomplete="off"
            />
            <button type="submit">Search</button>
          </div>
        </form>
        <p class="store-readout">
          {(storage().sessions as number | undefined)?.toLocaleString() || "0"} sessions ·{" "}
          {(storage().events as number | undefined)?.toLocaleString() || "0"} events
        </p>
      </div>

      <div class="overview-grid">
        <section class="panel-block">
          <div class="panel-title">
            <span class="eyebrow">recent sessions</span>
            <A href="/sessions">browse all →</A>
          </div>
          <div class="session-list-mini">
            <Show when={!sessions.loading} fallback={<p class="empty-state">Loading sessions...</p>}>
              <Show when={sessions().length} fallback={<p class="empty-state">No sessions match the active source filter.</p>}>
                <For each={sessions()}>
                  {(session) => (
                    <A href={`/sessions/${encodeURIComponent(session.id)}`} class="mini-session-row">
                      <SourceDot source={session.source} />
                      <span>
                        <strong>{session.title || session.clientSessionId}</strong>
                        <small>
                          {session.eventCount.toLocaleString()} events · {truncatePath(session.cwd)}
                        </small>
                      </span>
                      <time>{timeAgo(session.lastSeenAt)}</time>
                    </A>
                  )}
                </For>
              </Show>
            </Show>
          </div>
        </section>

        <section class="panel-block">
          <div class="panel-title">
            <span class="eyebrow">live activity</span>
            <span class={`live-inline live-inline--${live.status()}`}>{live.status()}</span>
          </div>
          <div class="live-feed">
            <Show when={live.events().length} fallback={<p class="empty-state">Listening for activity...</p>}>
              <For each={live.events()}>
                {(event) => (
                  <A href={`/sessions/${encodeURIComponent(event.sessionId)}`} class="live-row">
                    <SourceDot source={event.source} />
                    <KindBadge kind={event.eventType} />
                    <span>{event.title || event.toolName || event.eventType}</span>
                    <time>{timeAgo(event.observedAt)}</time>
                  </A>
                )}
              </For>
            </Show>
          </div>
        </section>
      </div>
    </section>
  );
}
