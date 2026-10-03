import { A } from "@solidjs/router";
import { createEffect, createMemo, createSignal, For, onCleanup, onMount, Show } from "solid-js";
import {
  ApiError,
  getSavedMeld,
  getSavedMeldJson,
  getUnassignedBraids,
  type ProjectSavedMeld,
} from "../lib/api";
import LazyDetails from "./events/blocks/LazyDetails";

export function savedBraidHref(id: string): string {
  return `/projects?${new URLSearchParams({ view: "braids", meld: id })}`;
}

export default function SavedBraidsReader(props: { selectedId?: string }) {
  const [items, setItems] = createSignal<ProjectSavedMeld[]>([]);
  const [nextBefore, setNextBefore] = createSignal<string | null>(null);
  const [listLoaded, setListLoaded] = createSignal(false);
  const [listLoading, setListLoading] = createSignal(false);
  const [listError, setListError] = createSignal<string | null>(null);
  const [detail, setDetail] = createSignal<ProjectSavedMeld | null>(null);
  const [detailLoading, setDetailLoading] = createSignal(false);
  const [detailError, setDetailError] = createSignal<string | null>(null);
  const [retryDetail, setRetryDetail] = createSignal(0);
  const [chooserOpen, setChooserOpen] = createSignal(false);
  const media =
    typeof window.matchMedia === "function" ? window.matchMedia("(max-width: 760px)") : null;
  const [narrow, setNarrow] = createSignal(media?.matches ?? false);
  const showList = () => !narrow() || chooserOpen() || !props.selectedId;
  let listAbort: AbortController | undefined;
  let detailRegion: HTMLDivElement | undefined;
  let chooserButton: HTMLButtonElement | undefined;

  onMount(() => {
    void loadPage();
    const resize = () => setNarrow(media?.matches ?? false);
    media?.addEventListener("change", resize);
    onCleanup(() => media?.removeEventListener("change", resize));
  });
  onCleanup(() => listAbort?.abort());

  async function loadPage() {
    if (listLoading()) return;
    const before = listLoaded() ? (nextBefore() ?? undefined) : undefined;
    const controller = new AbortController();
    listAbort = controller;
    setListLoading(true);
    setListError(null);
    try {
      const page = await getUnassignedBraids(before, controller.signal);
      if (controller.signal.aborted) return;
      setItems((existing) => {
        const known = new Set(existing.map((item) => item.id));
        return [...existing, ...page.items.filter((item) => !known.has(item.id))];
      });
      setNextBefore(page.nextBefore);
      setListLoaded(true);
    } catch (error) {
      if (!controller.signal.aborted) setListError(message(error, "Unable to load saved braids."));
    } finally {
      if (!controller.signal.aborted) setListLoading(false);
    }
  }

  createEffect(() => {
    const id = props.selectedId;
    retryDetail();
    setDetail(null);
    setDetailError(null);
    setDetailLoading(Boolean(id));
    if (!id) return;
    setChooserOpen(false);
    // Move to the selected reader now, never after a delayed request could steal newer focus.
    focusDetail();
    const controller = new AbortController();
    onCleanup(() => controller.abort());
    void getSavedMeld(id, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setDetail(value);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setDetailError(
          error instanceof ApiError && error.status === 404
            ? "This saved artifact could not be loaded (not found)."
            : message(error, "Unable to load this saved artifact."),
        );
      })
      .finally(() => {
        if (controller.signal.aborted) return;
        setDetailLoading(false);
      });
  });

  function focusDetail() {
    detailRegion?.focus({ preventScroll: true });
    detailRegion?.scrollIntoView?.({ block: "start" });
  }

  function selectCurrentItem(event: MouseEvent, id: string) {
    if (
      id !== props.selectedId ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    )
      return;
    event.preventDefault();
    setChooserOpen(false);
    focusDetail();
  }

  function closeChooser(event: KeyboardEvent) {
    if (event.key !== "Escape" || event.defaultPrevented || !narrow() || !chooserOpen()) return;
    event.preventDefault();
    setChooserOpen(false);
    chooserButton?.focus();
  }

  return (
    <section class="saved-braids-page" aria-labelledby="saved-braids-title">
      <header class="saved-braids-header">
        <div>
          <p class="eyebrow">Saved synthesis · unassigned</p>
          <h1 id="saved-braids-title">Saved braids</h1>
          <p>Read saved connections across source sessions, without assigning an owning project.</p>
        </div>
        <Show when={narrow() && props.selectedId}>
          <button
            ref={chooserButton}
            type="button"
            class="secondary-action"
            aria-expanded={showList()}
            aria-controls="saved-braids-list"
            onClick={() => setChooserOpen((open) => !open)}
          >
            Choose a saved braid
          </button>
        </Show>
      </header>
      <div class="saved-braids-workspace">
        <Show when={showList()}>
          <aside
            id="saved-braids-list"
            class="saved-braids-list"
            aria-label="Saved braid list"
            onKeyDown={closeChooser}
          >
            <p class="eyebrow">{items().length} loaded</p>
            <Show when={listLoaded() && !items().length}>
              <p>No saved unassigned braids yet.</p>
            </Show>
            <nav aria-label="Saved braids">
              <For each={items()}>
                {(item) => (
                  <a
                    href={savedBraidHref(item.id)}
                    noScroll
                    onClick={(event) => selectCurrentItem(event, item.id)}
                    aria-current={props.selectedId === item.id ? "page" : undefined}
                  >
                    <strong>{item.title}</strong>
                    <span>
                      Saved <time dateTime={item.createdAt}>{item.createdAt}</time>
                    </span>
                    <small>{item.sessions.length} source sessions</small>
                  </a>
                )}
              </For>
            </nav>
            <Show when={listLoading()}>
              <p role="status">Loading saved braids…</p>
            </Show>
            <Show when={listError()}>
              {(error) => (
                <div role="alert">
                  <p>{error()}</p>
                  <button type="button" class="secondary-action" onClick={() => void loadPage()}>
                    Retry saved braid list
                  </button>
                </div>
              )}
            </Show>
            <Show when={nextBefore() && !listError()}>
              <button
                type="button"
                class="secondary-action"
                disabled={listLoading()}
                onClick={() => void loadPage()}
              >
                Load more saved braids
              </button>
            </Show>
          </aside>
        </Show>
        <div
          ref={detailRegion}
          class="saved-braid-detail"
          tabIndex={-1}
          aria-label="Saved artifact"
          aria-busy={detailLoading()}
        >
          <Show
            when={props.selectedId}
            fallback={<p>Select a saved braid to read its synthesis and source sessions.</p>}
          >
            <Show when={!detailLoading()} fallback={<p role="status">Loading saved artifact…</p>}>
              <Show
                when={!detailError()}
                fallback={
                  <div role="alert">
                    <h2>Saved artifact unavailable</h2>
                    <p>{detailError()}</p>
                    <button
                      type="button"
                      class="secondary-action"
                      onClick={() => setRetryDetail((value) => value + 1)}
                    >
                      Retry saved artifact
                    </button>
                  </div>
                }
              >
                <Show when={detail()} keyed>
                  {(artifact) => <SavedBraidDetail artifact={artifact} />}
                </Show>
              </Show>
            </Show>
          </Show>
        </div>
      </div>
    </section>
  );
}

function SavedBraidDetail(props: { artifact: ProjectSavedMeld }) {
  const unassigned = () =>
    props.artifact.projectKey === null && props.artifact.canonicalKey === null;
  return (
    <Show
      when={unassigned()}
      fallback={
        <section>
          <h2>{props.artifact.title}</h2>
          <p>This saved meld belongs to a project; it is not an unassigned braid.</p>
          <Show when={props.artifact.projectKey}>
            {(key) => <A href={`/projects/${encodeURIComponent(key())}`}>Open owning project</A>}
          </Show>
        </section>
      }
    >
      <article>
        <p class="eyebrow">Saved synthesis</p>
        <h2>{props.artifact.title}</h2>
        <p class="saved-braid-save-time">
          Saved <time dateTime={props.artifact.createdAt}>{props.artifact.createdAt}</time>
        </p>
        <div class="saved-braid-body">{props.artifact.body}</div>
        <section aria-labelledby="saved-braid-generator">
          <h3 id="saved-braid-generator">Caller-declared provenance</h3>
          <p>
            These fields were supplied with the saved synthesis, not independently verified by Black
            Box.
          </p>
          <dl class="saved-braid-generator">
            <dt>Provider</dt>
            <dd>{props.artifact.provider}</dd>
            <dt>Model</dt>
            <dd>{props.artifact.model}</dd>
            <dt>Prompt version</dt>
            <dd>{props.artifact.promptVersion}</dd>
            <dt>Execution mode</dt>
            <dd>{props.artifact.executionMode}</dd>
            <dt>Saved from preview</dt>
            <dd>{props.artifact.savedFromPreview ? "Yes" : "No"}</dd>
          </dl>
        </section>
        <section aria-labelledby="saved-braid-sources">
          <h3 id="saved-braid-sources">Source sessions</h3>
          <p>
            In saved order. New braids keep source, working directory and client ID snapshots; older
            artifacts can fall back to current session values. Titles, times and event counts are
            current; deleted sources may have unavailable values and zero events.
          </p>
          <ol class="saved-braid-sources">
            <For each={props.artifact.sessions}>
              {(session) => (
                <li>
                  <A href={`/sessions/${encodeURIComponent(session.id)}?reveal=session`}>
                    {session.title || session.clientSessionId || session.id}
                  </A>
                  <dl>
                    <dt>Source</dt>
                    <dd>{session.source ?? "Unavailable"}</dd>
                    <dt>Working directory</dt>
                    <dd>{session.cwd ?? "Not recorded"}</dd>
                    <dt>Client session</dt>
                    <dd>{session.clientSessionId ?? "Unavailable"}</dd>
                    <dt>Internal session</dt>
                    <dd>{session.id}</dd>
                    <dt>Events</dt>
                    <dd>{session.eventCount}</dd>
                    <dt>Started</dt>
                    <dd>{session.startedAt ?? "Unavailable"}</dd>
                    <dt>Last seen</dt>
                    <dd>{session.lastSeenAt ?? "Unavailable"}</dd>
                  </dl>
                </li>
              )}
            </For>
          </ol>
        </section>
        <Show when={props.artifact.metadata && Object.keys(props.artifact.metadata).length}>
          <LazyDetails summary="Caller metadata (unverified)">
            <MetadataPreview artifactId={props.artifact.id} metadata={props.artifact.metadata!} />
          </LazyDetails>
        </Show>
      </article>
    </Show>
  );
}

const METADATA_LIMIT = 24_000;
function MetadataPreview(props: { artifactId: string; metadata: Record<string, unknown> }) {
  const raw = createMemo(() => JSON.stringify(props.metadata, null, 2));
  const preview = createMemo(() => {
    const value = raw();
    let end = Math.min(value.length, METADATA_LIMIT);
    if (end < value.length && /[\uD800-\uDBFF]/.test(value[end - 1])) end--;
    return value.slice(0, end);
  });
  const [downloading, setDownloading] = createSignal(false);
  const [downloadError, setDownloadError] = createSignal<string | null>(null);
  let downloadAbort: AbortController | undefined;
  onCleanup(() => downloadAbort?.abort());
  async function downloadArtifact() {
    if (downloading()) return;
    const controller = new AbortController();
    downloadAbort = controller;
    setDownloading(true);
    setDownloadError(null);
    try {
      const text = await getSavedMeldJson(props.artifactId, controller.signal);
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = "saved-artifact.json";
      link.click();
      const revoke = URL.revokeObjectURL.bind(URL);
      setTimeout(() => revoke(url), 0);
    } catch (error) {
      if (!controller.signal.aborted)
        setDownloadError(message(error, "Unable to download saved artifact."));
    } finally {
      if (!controller.signal.aborted) setDownloading(false);
    }
  }
  return (
    <div>
      <Show when={preview().length < raw().length}>
        <p>
          Metadata preview truncated to {preview().length.toLocaleString()} of{" "}
          {raw().length.toLocaleString()} UTF-16 code units.
        </p>
      </Show>
      <pre class="saved-braid-metadata">{preview()}</pre>
      <p>
        Parsed metadata preview; large numbers may be rounded for display. The download preserves
        the saved JSON response.
      </p>
      <button
        type="button"
        class="secondary-action"
        disabled={downloading()}
        onClick={() => void downloadArtifact()}
      >
        {downloading() ? "Downloading saved artifact…" : "Download saved artifact JSON"}
      </button>
      <Show when={downloadError()}>{(error) => <p role="alert">{error()}</p>}</Show>
    </div>
  );
}

function message(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
