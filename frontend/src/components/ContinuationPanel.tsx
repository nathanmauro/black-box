import { A } from "@solidjs/router";
import { createSignal, For, onCleanup, Show } from "solid-js";
import {
  getContinuations,
  synthesizeContinuation,
  type ContinuationCandidate,
  type ContinuationResponse,
  type ContinuationSynthesisResponse,
} from "../lib/api";
import {
  continuationBasisLabel,
  promptWithAbsoluteLinks,
  recordedAgo,
  synthesisReasonText,
} from "../lib/continuations";

/**
 * Prompts from the record for one project path. Nothing is fetched until Build prompts, and
 * nothing is remembered: leaving the page or choosing another project discards the result.
 */
export default function ContinuationPanel(props: { project: string; disabled?: boolean }) {
  const [response, setResponse] = createSignal<ContinuationResponse | null>(null);
  const [loading, setLoading] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);
  let token = 0;
  onCleanup(() => {
    token += 1;
  });

  async function build() {
    const current = ++token;
    setLoading(true);
    setError(null);
    try {
      const next = await getContinuations(props.project);
      if (current === token) setResponse(next);
    } catch (err) {
      if (current === token) setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (current === token) setLoading(false);
    }
  }

  return (
    <section class="continuation-panel" aria-labelledby="continuation-title">
      <div class="recall-briefing-head">
        <h2 id="continuation-title">Prompts from the record</h2>
        <button
          type="button"
          class="secondary-action"
          disabled={loading() || props.disabled}
          onClick={() => void build()}
        >
          {loading() ? "Building…" : "Build prompts"}
        </button>
      </div>
      <p class="recall-hint">
        Builds up to three starting prompts from this project's recorded handoffs and current
        decisions in the last year. They restate the record; nothing is run, saved or verified.
      </p>
      <Show when={error()}>
        {(message) => (
          <p class="inline-error" role="alert">
            Prompts unavailable: {message()}
          </p>
        )}
      </Show>
      <Show when={response()}>
        {(resolved) => (
          <>
            <ul class="continuation-warnings" aria-label="Prompt warnings">
              <For each={resolved().warnings}>{(warning) => <li>{warning.message}</li>}</For>
            </ul>
            <p class="recall-hint">
              Considered {resolved().retrieval.handoffs} handoffs and{" "}
              {resolved().retrieval.decisions} current decisions
              <Show when={resolved().newestEvidenceAt}>
                {(at) => (
                  <>
                    {" "}
                    · newest capture <time datetime={at()}>{at()}</time>
                  </>
                )}
              </Show>
              .
            </p>
            <Show when={resolved().candidates.length}>
              <div class="continuation-cards">
                <For each={resolved().candidates}>
                  {(candidate) => (
                    <ContinuationCard
                      candidate={candidate}
                      project={props.project}
                      withinHours={resolved().withinHours}
                      synthesisAvailable={resolved().synthesisAvailable === true}
                    />
                  )}
                </For>
              </div>
            </Show>
          </>
        )}
      </Show>
    </section>
  );
}

function ContinuationCard(props: {
  candidate: ContinuationCandidate;
  project: string;
  withinHours: number;
  synthesisAvailable: boolean;
}) {
  const [copyStatus, setCopyStatus] = createSignal("");
  const [restating, setRestating] = createSignal(false);
  const [restatement, setRestatement] = createSignal<ContinuationSynthesisResponse | null>(null);
  const [restateError, setRestateError] = createSignal<string | null>(null);
  let summary!: HTMLElement;
  let alive = true;
  onCleanup(() => {
    alive = false;
  });

  // One explicit model call per click; the deterministic prompt is never replaced.
  async function restate() {
    setRestating(true);
    setRestateError(null);
    setRestatement(null);
    try {
      const result = await synthesizeContinuation({
        project: props.project,
        withinHours: props.withinHours,
        candidateId: props.candidate.id,
      });
      if (alive) setRestatement(result);
    } catch (err) {
      if (alive) setRestateError(err instanceof Error ? err.message : String(err));
    } finally {
      if (alive) setRestating(false);
    }
  }

  async function copyPrompt() {
    try {
      await navigator.clipboard.writeText(
        promptWithAbsoluteLinks(props.candidate, window.location.origin),
      );
      setCopyStatus("Prompt copied with absolute source links.");
    } catch {
      setCopyStatus("Clipboard unavailable. Allow clipboard access and try again.");
    }
  }

  return (
    <article class="continuation-card" aria-label={props.candidate.title}>
      <div class="continuation-card-head">
        <span class="continuation-basis">{continuationBasisLabel(props.candidate.basis)}</span>
        <span>{recordedAgo(props.candidate.ageDays)}</span>
        <Show when={props.candidate.stale}>
          <span class="continuation-stale">Stale · older than 14 days</span>
        </Show>
      </div>
      <h3>{props.candidate.title}</h3>
      <p class="recall-hint">{props.candidate.why}</p>
      <Show when={props.candidate.caveats.length}>
        <ul class="continuation-caveats" aria-label="Caveats">
          <For each={props.candidate.caveats}>
            {(caveat) => (
              <li>
                {caveat.message}
                <Show when={caveat.href}>
                  {(href) => (
                    <>
                      {" "}
                      <A href={href()}>Open evidence</A>
                    </>
                  )}
                </Show>
              </li>
            )}
          </For>
        </ul>
      </Show>
      <div class="continuation-sources">
        <span>Sources</span>
        <ul>
          <For each={props.candidate.evidence}>
            {(item) => (
              <li>
                <A href={item.href}>
                  {item.kind} · {item.field}
                </A>{" "}
                <time datetime={item.observedAt || undefined}>{item.observedAt}</time>
              </li>
            )}
          </For>
        </ul>
      </div>
      <details
        class="continuation-inspect"
        onKeyDown={(event) => {
          // Only an open prompt consumes Escape, so the page's own Escape handling is unchanged.
          if (event.key !== "Escape" || !event.currentTarget.open) return;
          event.currentTarget.open = false;
          summary.focus();
          event.preventDefault();
        }}
      >
        <summary ref={summary}>Inspect prompt</summary>
        <pre class="continuation-prompt">{props.candidate.prompt}</pre>
      </details>
      <div class="continuation-actions">
        <button type="button" class="secondary-action" onClick={() => void copyPrompt()}>
          Copy prompt
        </button>
        <Show when={props.synthesisAvailable}>
          <button
            type="button"
            class="secondary-action"
            disabled={restating()}
            onClick={() => void restate()}
          >
            {restating() ? "Restating…" : "Restate with local model"}
          </button>
        </Show>
        <p role="status">{copyStatus()}</p>
      </div>
      <Show when={restateError()}>
        {(message) => (
          <p class="inline-error" role="alert">
            Restatement failed: {message()}
          </p>
        )}
      </Show>
      <Show when={restatement()}>
        {(result) => (
          <Show
            when={result().synthesized}
            fallback={
              <p class="recall-hint" role="status">
                {synthesisReasonText(result().synthesis.reason)}
              </p>
            }
          >
            {(synthesized) => (
              <section class="continuation-restatement" aria-label="Local model restatement">
                <p class="continuation-restatement-label">Model restatement · unverified</p>
                <p class="recall-hint">
                  Written by {synthesized().model || "the local model"} from the cited records; it
                  can be wrong. The prompt from the record is unchanged, and Copy prompt copies it.
                </p>
                <p class="continuation-restatement-text">{synthesized().text}</p>
                <ul class="continuation-citations" aria-label="Cited records">
                  <For each={synthesized().citations}>
                    {(citation) => (
                      <li>
                        <A href={citation.href}>
                          [{citation.handle}] {citation.kind}
                        </A>{" "}
                        <time datetime={citation.observedAt || undefined}>
                          {citation.observedAt}
                        </time>
                      </li>
                    )}
                  </For>
                </ul>
              </section>
            )}
          </Show>
        )}
      </Show>
    </article>
  );
}
