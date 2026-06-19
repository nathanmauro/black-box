import { useNavigate, useParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, Show } from "solid-js";
import { createVirtualizer } from "@tanstack/solid-virtual";
import SourceDot from "../components/SourceDot";
import { EventRenderer } from "../components/events/EventRow";
import {
  getProjectMelds,
  getProjects,
  getProjectSessions,
  getProjectTimeline,
  previewProjectMeld,
  saveProjectMeld,
  type AgentEvent,
  type AgentSession,
  type ProjectMeld,
  type ProjectMeldSaveRequest,
  type ProjectMeldPreviewResponse,
  type ProjectMeldSessionRef,
  type ProjectSummary,
  type ProjectTimelineBlock,
  type ProjectTimelineResponse,
} from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import { sourceFilter } from "../lib/stores";

const TIMELINE_LIMIT = 250;

export default function ProjectsPage() {
  let projectListRef: HTMLDivElement | undefined;
  let sessionListRef: HTMLDivElement | undefined;
  let timelineRef: HTMLDivElement | undefined;

  const params = useParams<{ projectKey?: string }>();
  const navigate = useNavigate();
  const [selectedSessionIds, setSelectedSessionIds] = createSignal<Set<string>>(new Set());
  const [preview, setPreview] = createSignal<ProjectMeldPreviewResponse | null>(null);
  const [previewError, setPreviewError] = createSignal<string | null>(null);
  const [previewLoading, setPreviewLoading] = createSignal(false);
  const [saveError, setSaveError] = createSignal<string | null>(null);
  const [saveLoading, setSaveLoading] = createSignal(false);
  const [savedMeldId, setSavedMeldId] = createSignal<string | null>(null);
  const [projects] = createResource(async () => getProjects(), { initialValue: [] as ProjectSummary[] });
  const selectedKey = createMemo(() => params.projectKey || projects()[0]?.projectKey || "");
  const selectedProject = createMemo(() => projects().find((project) => project.projectKey === selectedKey()));
  const projectKeyWithFilter = createMemo(() =>
    selectedKey() ? { projectKey: selectedKey(), sourceKey: sourceFilter.key() } : null,
  );
  const [sessions] = createResource(
    projectKeyWithFilter,
    async (input) => (input ? sourceFilter.matches(await getProjectSessions(input.projectKey)) : []),
    { initialValue: [] as AgentSession[] },
  );
  const [timeline, { refetch: refetchTimeline }] = createResource(
    projectKeyWithFilter,
    async (input) => (input ? filterTimeline(await getProjectTimeline(input.projectKey, TIMELINE_LIMIT, 0)) : emptyTimeline()),
    { initialValue: emptyTimeline() },
  );
  const [melds, { refetch: refetchMelds }] = createResource(
    selectedKey,
    async (projectKey) => (projectKey ? getProjectMelds(projectKey) : []),
    { initialValue: [] as ProjectMeld[] },
  );
  const timelineItems = createMemo(() => timeline().items);
  const selectedCount = createMemo(() => selectedSessionIds().size);
  const previewText = createMemo(() => previewBody(preview()));

  createEffect(() => {
    selectedKey();
    setSelectedSessionIds(new Set<string>());
    setPreview(null);
    setPreviewError(null);
    setSaveError(null);
    setSavedMeldId(null);
  });

  const projectVirtualizer = createVirtualizer({
    get count() {
      return projects().length;
    },
    getScrollElement: () => projectListRef || null,
    estimateSize: () => 84,
    overscan: 10,
    initialRect: { width: 320, height: 640 },
  });
  const sessionVirtualizer = createVirtualizer({
    get count() {
      return sessions().length;
    },
    getScrollElement: () => sessionListRef || null,
    estimateSize: () => 74,
    overscan: 10,
    initialRect: { width: 360, height: 360 },
  });
  const timelineVirtualizer = createVirtualizer({
    get count() {
      return timelineItems().length;
    },
    getScrollElement: () => timelineRef || null,
    estimateSize: () => 172,
    overscan: 8,
    initialRect: { width: 720, height: 680 },
  });

  function toggleSession(sessionId: string) {
    setSelectedSessionIds((current) => {
      const next = new Set(current);
      if (next.has(sessionId)) next.delete(sessionId);
      else next.add(sessionId);
      return next;
    });
  }

  async function runPreview() {
    const projectKey = selectedKey();
    const sessionIds = [...selectedSessionIds()];
    if (!projectKey || !sessionIds.length) return;
    setPreviewLoading(true);
    setPreviewError(null);
    setSaveError(null);
    setSavedMeldId(null);
    try {
      setPreview(await previewProjectMeld(projectKey, sessionIds));
    } catch (err) {
      setPreviewError(err instanceof Error ? err.message : String(err));
    } finally {
      setPreviewLoading(false);
    }
  }

  async function savePreview() {
    const resolved = preview();
    if (!resolved) return;
    const sessionIds = (resolved.sessions || []).map((session) => session.id).filter(Boolean);
    const body = previewBody(resolved);
    if (!sessionIds.length) {
      setSaveError("Saved melds require at least one source session.");
      return;
    }
    if (!body.trim()) {
      setSaveError("Saved melds require preview text.");
      return;
    }
    const request: ProjectMeldSaveRequest = {
      projectKey: resolved.projectKey,
      title: resolved.title || "Meld preview",
      body,
      provider: resolved.provider || "local",
      model: resolved.model || "context-bundle",
      executionMode: resolved.executionMode || "export_bundle",
      savedFromPreview: true,
      sessionIds,
      metadata: {
        sessionCount: resolved.sessionCount,
        evidenceCount: resolved.evidenceCount,
        bundleChars: resolved.bundleChars,
        degradationNotes: resolved.degradationNotes || [],
      },
    };
    setSaveLoading(true);
    setSaveError(null);
    try {
      const saved = await saveProjectMeld(request);
      setSavedMeldId(saved.id);
      await Promise.all([refetchMelds(), refetchTimeline()]);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaveLoading(false);
    }
  }

  return (
    <section class="projects-page">
      <aside class="project-list-pane">
        <div class="pane-head">
          <span class="eyebrow">projects</span>
          <span>{projects().length.toLocaleString()}</span>
        </div>
        <Show when={!projects.loading} fallback={<p class="empty-state project-pad">Loading projects...</p>}>
          <Show when={projects().length} fallback={<p class="empty-state project-pad">No projects found.</p>}>
            <div class="project-list" ref={projectListRef}>
              <div class="virtual-spacer" style={{ height: `${projectVirtualizer.getTotalSize()}px` }}>
                <For each={projectVirtualizer.getVirtualItems()}>
                  {(row) => {
                    const project = () => projects()[row.index];
                    return (
                      <button
                        type="button"
                        classList={{
                          "project-row": true,
                          "project-row--active": project()?.projectKey === selectedKey(),
                        }}
                        style={{ transform: `translateY(${row.start}px)` }}
                        onClick={() => navigate(`/projects/${encodeURIComponent(project().projectKey)}`)}
                      >
                        <strong>{project().label}</strong>
                        <small>{truncatePath(project().canonicalKey)}</small>
                        <span>
                          {project().sessionCount.toLocaleString()} sessions · {project().eventCount.toLocaleString()} events
                        </span>
                      </button>
                    );
                  }}
                </For>
              </div>
            </div>
          </Show>
        </Show>
      </aside>

      <section class="project-detail-pane">
        <Show
          when={selectedProject()}
          fallback={
            <div class="empty-detail">
              <p class="eyebrow">project detail</p>
              <h1>Select a project</h1>
              <p>Projects are derived from recorded working directories.</p>
            </div>
          }
        >
          {(project) => (
            <>
              <header class="project-detail-header">
                <div>
                  <p class="eyebrow">hybrid storyline</p>
                  <h1>{project().label}</h1>
                  <p>{truncatePath(project().canonicalKey)}</p>
                </div>
                <div class="project-stat-strip">
                  <Metric label="sessions" value={project().sessionCount} />
                  <Metric label="events" value={project().eventCount} />
                  <Metric label="melds" value={project().savedMeldCount} />
                  <span>{timeAgo(project().lastSeenAt)}</span>
                </div>
              </header>

              <div class="project-detail-grid">
                <section class="project-storyline">
                  <div class="pane-head">
                    <span class="eyebrow">storyline timeline</span>
                    <span>
                      {timelineItems().length.toLocaleString()} / {timeline().count.toLocaleString()}
                    </span>
                  </div>
                  <div class="project-timeline" ref={timelineRef}>
                    <Show when={!timeline.loading} fallback={<p class="empty-state">Loading timeline...</p>}>
                      <Show when={timelineItems().length} fallback={<p class="empty-state">No timeline blocks match this source filter.</p>}>
                        <div class="virtual-spacer" style={{ height: `${timelineVirtualizer.getTotalSize()}px` }}>
                          <For each={timelineVirtualizer.getVirtualItems()}>
                            {(row) => {
                              const block = () => timelineItems()[row.index];
                              return (
	                                  <div class="project-timeline-row" style={{ transform: `translateY(${row.start}px)` }}>
	                                    <div class="timeline-block-label">
	                                      <span>{block().blockType || block().sourceType || "event"}</span>
	                                      <span>{block().sessionTitle || block().clientSessionId || block().headline}</span>
	                                    </div>
	                                    <Show
	                                      when={block().sourceType === "saved_meld"}
	                                      fallback={<EventRenderer event={timelineBlockToEvent(block())} />}
	                                    >
	                                      <SavedMeldTimelineCard block={block()} />
	                                    </Show>
	                                  </div>
	                                );
                            }}
                          </For>
                        </div>
                      </Show>
                    </Show>
                  </div>
                </section>

                <aside class="meld-builder">
                  <div class="pane-head">
                    <span class="eyebrow">meld builder</span>
                    <span>{melds().length.toLocaleString()} saved</span>
                  </div>
                  <div class="meld-builder-body">
                    <div class="meld-session-list" ref={sessionListRef}>
                      <Show when={!sessions.loading} fallback={<p class="empty-state">Loading sessions...</p>}>
                        <Show when={sessions().length} fallback={<p class="empty-state">No sessions match this source filter.</p>}>
                          <div class="virtual-spacer" style={{ height: `${sessionVirtualizer.getTotalSize()}px` }}>
                            <For each={sessionVirtualizer.getVirtualItems()}>
                              {(row) => {
                                const session = () => sessions()[row.index];
                                return (
                                  <label class="meld-session-row" style={{ transform: `translateY(${row.start}px)` }}>
                                    <input
                                      type="checkbox"
                                      aria-label={`Select ${session().title || session().clientSessionId}`}
                                      checked={selectedSessionIds().has(session().id)}
                                      onChange={() => toggleSession(session().id)}
                                    />
                                    <SourceDot source={session().source} />
                                    <span>
                                      <strong>{session().title || session().clientSessionId}</strong>
                                      <small>
                                        {session().eventCount.toLocaleString()} events · {timeAgo(session().lastSeenAt)}
                                      </small>
                                    </span>
                                  </label>
                                );
                              }}
                            </For>
                          </div>
                        </Show>
                      </Show>
                    </div>

                    <button
                      type="button"
                      class="primary-action meld-preview-button"
                      disabled={!selectedCount() || previewLoading()}
                      onClick={() => void runPreview()}
                    >
                      {previewLoading() ? "Previewing..." : "Preview meld"}
                    </button>
                    <p class="meld-selection">{selectedCount().toLocaleString()} selected</p>

                    <Show when={previewError()}>
                      {(message) => <p class="inline-error">Preview failed: {message()}</p>}
                    </Show>

                    <Show when={preview()}>
                      {(resolved) => (
                        <section class="meld-preview">
                          <div class="meld-preview-meta">
                            <strong>{resolved().title || "Meld preview"}</strong>
                            <span>
                              {resolved().sessionCount.toLocaleString()} sessions · {resolved().evidenceCount.toLocaleString()} evidence
                            </span>
                          </div>
	                          <pre>{previewText()}</pre>
	                          <div class="meld-preview-actions">
	                            <button
	                              type="button"
	                              class="secondary-action meld-save-button"
	                              disabled={saveLoading() || Boolean(savedMeldId())}
	                              onClick={() => void savePreview()}
	                            >
	                              {savedMeldId() ? "Saved" : saveLoading() ? "Saving..." : "Save meld"}
	                            </button>
	                          </div>
	                          <For each={resolved().degradationNotes || []}>{(note) => <p class="meld-note">{note}</p>}</For>
	                        </section>
	                      )}
	                    </Show>
	                    <Show when={saveError()}>
	                      {(message) => <p class="inline-error">Save failed: {message()}</p>}
	                    </Show>
	                    <Show when={savedMeldId()}>
	                      <p class="meld-save-status">Saved meld.</p>
	                    </Show>
	                    <SavedMeldList melds={melds()} />
	                  </div>
	                </aside>
              </div>
            </>
          )}
        </Show>
      </section>
    </section>
  );
}

function Metric(props: { label: string; value: number }) {
  return (
    <span>
      <strong>{props.value.toLocaleString()}</strong> {props.label}
    </span>
  );
}

function SavedMeldTimelineCard(props: { block: ProjectTimelineBlock }) {
  const metadata = createMemo(() => metadataRecord(props.block.metadata));
  return (
    <article class="saved-meld-card">
      <div class="saved-meld-card-head">
        <strong>{props.block.headline || "Saved meld"}</strong>
        <time>{timeAgo(props.block.observedAt || new Date(0).toISOString())}</time>
      </div>
      <div class="saved-meld-provenance">
        <span>{metadataValue(metadata(), "provider", "local")}</span>
        <span>{metadataValue(metadata(), "model", "context-bundle")}</span>
        <span>{metadataValue(metadata(), "executionMode", "export_bundle")}</span>
      </div>
      <p>{props.block.text}</p>
      <SourceSessionLinks sessions={props.block.sourceSessions || []} />
    </article>
  );
}

function SavedMeldList(props: { melds: ProjectMeld[] }) {
  return (
    <Show when={props.melds.length}>
      <section class="saved-meld-list">
        <div class="saved-meld-list-head">
          <span class="eyebrow">saved melds</span>
          <span>{props.melds.length.toLocaleString()}</span>
        </div>
        <For each={props.melds}>
          {(meld) => (
            <article class="saved-meld-row">
              <strong>{meld.title}</strong>
              <small>
                {meld.provider} · {meld.model} · {timeAgo(meld.createdAt)}
              </small>
              <p>{meld.body}</p>
              <SourceSessionLinks sessions={meld.sessions || []} />
            </article>
          )}
        </For>
      </section>
    </Show>
  );
}

function SourceSessionLinks(props: { sessions: ProjectMeldSessionRef[] }) {
  return (
    <Show when={props.sessions.length}>
      <div class="meld-source-links">
        <span>source sessions</span>
        <For each={props.sessions}>
          {(session) => <a href={`/sessions/${encodeURIComponent(session.id)}`}>{session.title || session.clientSessionId}</a>}
        </For>
      </div>
    </Show>
  );
}

function filterTimeline(response: ProjectTimelineResponse): ProjectTimelineResponse {
  return {
    ...response,
    items: sourceFilter.matches(response.items.filter((item) => item.source)),
  };
}

function emptyTimeline(): ProjectTimelineResponse {
  return {
    projectKey: "",
    canonicalKey: "",
    label: "",
    limit: TIMELINE_LIMIT,
    offset: 0,
    count: 0,
    items: [],
  };
}

function previewBody(response: ProjectMeldPreviewResponse | null): string {
  return response?.preview || response?.bundle || "";
}

function metadataRecord(metadata: unknown): Record<string, unknown> {
  return metadata && typeof metadata === "object" && !Array.isArray(metadata) ? (metadata as Record<string, unknown>) : {};
}

function metadataValue(metadata: Record<string, unknown>, key: string, fallback: string): string {
  const value = metadata[key];
  return typeof value === "string" && value.trim() ? value : fallback;
}

function timelineBlockToEvent(block: ProjectTimelineBlock): AgentEvent {
  return {
    id: block.id,
    sessionId: block.sessionId || block.id,
    source: block.source || "unknown",
    clientSessionId: block.clientSessionId || "",
    eventType: normalizeEventType(block.eventType || block.blockType || block.sourceType),
    role: block.role || undefined,
    text: block.text || block.headline || "",
    toolName: block.toolName || undefined,
    toolInputJson: block.toolInputJson || undefined,
    toolOutputJson: block.toolOutputJson || undefined,
    metadata: timelineMetadata(block),
    observedAt: block.observedAt || new Date(0).toISOString(),
  };
}

function timelineMetadata(block: ProjectTimelineBlock): unknown {
  const metadata =
    block.metadata && typeof block.metadata === "object" && !Array.isArray(block.metadata)
      ? { ...(block.metadata as Record<string, unknown>) }
      : {};
  const eventType = normalizeEventType(block.eventType || block.blockType || block.sourceType);
  if (eventType === "Decision") {
    if (!metadata.decision && block.headline) metadata.decision = block.headline;
    if (!metadata.rationale && block.text) metadata.rationale = block.text;
  }
  if (eventType === "Handoff") {
    if (!metadata.contextSummary && block.text) metadata.contextSummary = block.text;
  }
  if (eventType === "Observation") {
    if (!metadata.observation && block.text) metadata.observation = block.text;
  }
  return Object.keys(metadata).length ? metadata : block.metadata;
}

function normalizeEventType(value: string | null | undefined): string {
  const normalized = String(value || "Timeline").toLowerCase();
  if (normalized === "decision") return "Decision";
  if (normalized === "handoff") return "Handoff";
  if (normalized === "observation") return "Observation";
  return value || "Timeline";
}
