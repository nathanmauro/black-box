export type AgentSession = {
  id: string;
  source: string;
  clientSessionId: string;
  title: string;
  cwd?: string | null;
  summary?: string | null;
  firstHumanTurn?: string | null;
  spawnedBy?: string | null;
  startedAt: string;
  lastSeenAt: string;
  eventCount: number;
};

export type AgentEvent = {
  id: string;
  sessionId: string;
  source: string;
  clientSessionId: string;
  turnId?: string | null;
  eventType: string;
  role?: string | null;
  text?: string | null;
  humanText?: string | null;
  toolName?: string | null;
  toolInputJson?: string | null;
  toolOutputJson?: string | null;
  metadata?: unknown;
  observedAt: string;
};

export type SessionTranscriptResponse = {
  sessionId: string;
  available: boolean;
  complete: boolean;
  reason?: string | null;
  limit: number;
  count: number;
  events: AgentEvent[];
  nextBefore?: string | null;
};

export type SessionTranscriptParams = {
  limit?: number;
  before?: string;
  q?: string;
  humanOnly?: boolean;
};

export type EventFeedItem = AgentEvent & {
  cwd?: string | null;
  sessionTitle?: string | null;
};

export type EventFeedResponse = {
  limit: number;
  count: number;
  items: EventFeedItem[];
  nextBefore?: string | null;
};

export type EventFeedParams = {
  q?: string;
  limit?: number;
  before?: string;
  since?: string;
  meaningful?: boolean;
  humanOnly?: boolean;
};

export type FacetValueCount = {
  value: string;
  count: number;
};

export type EventFacetFields = {
  source: FacetValueCount[];
  kind: FacetValueCount[];
  tool: FacetValueCount[];
  project: FacetValueCount[];
};

// Counts degrade honestly (spec §6.5): total/fields are null together (reason names why, e.g.
// "backfill") and consumers must omit the number rather than showing a stale or partial one.
export type EventFacetCounts = {
  total: number | null;
  fields: EventFacetFields | null;
  reason?: string | null;
};

export type ElasticHealth = {
  enabled?: boolean;
  available?: boolean;
  detail?: string;
  [key: string]: unknown;
};

export type SearchResponse = {
  query: string;
  local: AgentEvent[];
  elastic: Array<AgentEvent | Record<string, unknown>>;
  elasticHealth?: ElasticHealth;
};

export type RecalledItem = {
  eventId: string;
  sessionId: string;
  kind: string;
  source: string;
  clientSessionId?: string | null;
  repo?: string | null;
  observedAt?: string | null;
  headline?: string | null;
  rationale?: string | null;
  alternatives?: string[] | null;
  confidence?: number | null;
  openLoops?: string[] | null;
  nextAction?: string | null;
  toAgent?: string | null;
  score?: number | null;
  supersedesEventId?: string | null;
  supersededByEventId?: string | null;
  // Idea recall items carry the idea title as headline and its oneLiner as rationale; the backend
  // RecalledItem has no origin, status, or legs, so the card links to the Ideas view for those.
};

export type RecallResult = {
  scope?: string | null;
  withinHours: number;
  kinds: string[];
  count: number;
  items: RecalledItem[];
  mode?: "hybrid" | "lexical" | string | null;
};

export type IdeaOrigin = "human-aside" | "agent-proposed" | "joint";

export type IdeaStatus =
  "untouched" | "partially-built" | "built-unused" | "superseded" | "tracked";

export type CaptureIdeaRequest = {
  source: string;
  clientSessionId: string;
  repo?: string;
  title: string;
  oneLiner: string;
  origin: IdeaOrigin;
  quote?: string;
  sourceRef?: string;
  legs?: number;
  status?: IdeaStatus;
  connects?: string[];
  resumeStep?: string;
  link?: string;
  notes?: string;
  ideaKey?: string;
};

export type IngestResponse = {
  eventId: string;
  sessionId: string;
  source: string;
  clientSessionId: string;
  eventType: string;
  [key: string]: unknown;
};

// The latest event per ideaKey (GET /api/ideas). Origin and status stay open strings so a value
// the server adds later still renders instead of failing the page.
export type IdeaView = {
  eventId: string;
  sessionId: string;
  source: string;
  clientSessionId: string;
  repo?: string | null;
  title: string;
  oneLiner: string;
  origin: IdeaOrigin | string;
  quote?: string | null;
  sourceRef?: string | null;
  legs: number | null;
  status: IdeaStatus | string;
  connects: string[];
  resumeStep?: string | null;
  link?: string | null;
  notes?: string | null;
  ideaKey: string;
  capturedAt: string;
  firstCapturedAt: string;
  revisions: number;
  migratedFrom: string | null;
};

export type IdeaListResponse = {
  items: IdeaView[];
  count: number;
};

export type IdeaListParams = {
  status?: string[];
  origin?: string;
  project?: string;
  q?: string;
  limit?: number;
};

export type IdeaMigrationCandidate = {
  observationId: string;
  sessionId: string;
  idea: Partial<CaptureIdeaRequest> & Record<string, unknown>;
  warnings: string[];
  alreadyMigrated: boolean;
  createdEventId: string | null;
};

export type IdeaMigrationResult = {
  apply: boolean;
  candidates: IdeaMigrationCandidate[];
  created: number;
  skipped: number;
};

export type ProjectScope = {
  projectKey: string;
  canonicalKey: string;
  label: string;
  primary: boolean;
  source?: "manual" | "nested-worktree" | "git-commondir" | "codex-voice" | null;
};

export type ProjectSummary = {
  projectKey: string;
  canonicalKey: string;
  label: string;
  sessionCount: number;
  eventCount: number;
  savedMeldCount: number;
  firstSeenAt?: string | null;
  lastSeenAt?: string | null;
  scopes?: ProjectScope[];
};

export type CodeProjectScope = {
  projectKey: string;
  root: string;
};

export type CodeReference = {
  projectKey: string;
  relativePath: string;
  line?: number;
  column?: number;
  commit?: string;
};

export type CodeNavigationResult = {
  status: "opened" | "revealed" | string;
};

export type ProjectAlias = {
  id: string;
  aliasKey: string;
  canonicalKey: string;
  source: string;
  createdAt: string;
};

export type ProjectTimelineBlock = {
  id: string;
  sourceType?: string | null;
  blockType?: string | null;
  headline?: string | null;
  text?: string | null;
  eventType?: string | null;
  role?: string | null;
  source: string;
  clientSessionId?: string | null;
  sessionId?: string | null;
  sessionTitle?: string | null;
  cwd?: string | null;
  toolName?: string | null;
  toolInputJson?: string | null;
  toolOutputJson?: string | null;
  metadata?: unknown;
  observedAt?: string | null;
  sourceSessions?: ProjectMeldSessionRef[] | null;
};

export type ProjectTimelineResponse = {
  projectKey: string;
  canonicalKey: string;
  label: string;
  limit: number;
  offset: number;
  count: number;
  items: ProjectTimelineBlock[];
};

export type TrajectoryCaptureKind = "decision" | "handoff" | "observation" | "meld" | "projection";

export type TrajectoryPath = {
  title?: string | null;
  description?: string | null;
  confidence?: number | null;
};

export type TrajectoryCapture = {
  id: string;
  kind: TrajectoryCaptureKind;
  sessionId?: string | null;
  sessionTitle?: string | null;
  clientSessionId?: string | null;
  source?: string | null;
  headline?: string | null;
  text?: string | null;
  rationale?: string | null;
  alternatives?: string[] | null;
  openLoops?: string[] | null;
  nextAction?: string | null;
  toAgent?: string | null;
  confidence?: number | null;
  paths?: TrajectoryPath[] | null;
  observedAt?: string | null;
};

export type TrajectoryTask = {
  id: string;
  title: string;
  status: string;
  priority: number;
  updatedAt?: string | null;
};

export type ProjectTrajectoryResponse = {
  projectKey: string;
  canonicalKey: string;
  label: string;
  generatedAt: string;
  totalCaptures: number;
  captures: TrajectoryCapture[];
  tasks: TrajectoryTask[];
};

export type ProjectMeldSessionRef = {
  id: string;
  source: string;
  clientSessionId: string;
  title: string;
  cwd?: string | null;
  eventCount: number;
  startedAt?: string | null;
  lastSeenAt?: string | null;
};

export type ProjectSavedMeld = {
  id: string;
  projectKey: string;
  canonicalKey: string;
  title: string;
  body: string;
  provider: string;
  model: string;
  promptVersion: string;
  executionMode: string;
  savedFromPreview: boolean;
  metadata?: Record<string, unknown> | null;
  createdAt: string;
  sessions: ProjectMeldSessionRef[];
};

export type ProjectMeld = ProjectSavedMeld;

export type ProjectMeldSaveRequest = {
  projectKey: string;
  title: string;
  body: string;
  provider: string;
  model: string;
  executionMode: string;
  savedFromPreview: boolean;
  sessionIds: string[];
  promptVersion?: string;
  metadata?: Record<string, unknown>;
};

export type ProjectMeldPreviewResponse = {
  status: string;
  executionMode?: string | null;
  provider?: string | null;
  model?: string | null;
  projectKey: string;
  canonicalKey: string;
  title?: string | null;
  preview?: string | null;
  bundle?: string | null;
  sessions: ProjectMeldSessionRef[];
  sessionCount: number;
  evidenceCount: number;
  bundleChars: number;
  degradationNotes?: string[] | null;
};

export type FieldInfo = {
  name: string;
  type?: string;
  searchable?: boolean;
  aggregatable?: boolean;
  enumerable?: boolean;
};

export type AskComponentStatus = {
  enabled: boolean;
  available: boolean;
  detail?: string;
};

export type AskStatus = {
  memoryIndex?: string;
  elasticsearch?: AskComponentStatus;
  embeddings?: AskComponentStatus;
  chat?: AskComponentStatus;
  embeddingModel?: string;
  embeddingDimensions?: number;
  defaultAskCitations?: number;
  defaultRetrieveResults?: number;
  retrievalMode?: string;
};

export type AskCitation = {
  number: number;
  id: string;
  title?: string | null;
  source?: string | null;
  sourcePath?: string | null;
  sessionId?: string | null;
  clientSessionId?: string | null;
  timestamp?: string | null;
  snippet?: string | null;
  score?: number;
};

export type AskResponse = {
  question: string;
  answer: string;
  retrievalMode?: string;
  degraded?: boolean;
  citations: AskCitation[];
};

export type ApiStatus = {
  storage?: {
    sessions?: number;
    events?: number;
    [key: string]: unknown;
  };
  localAi?: Record<string, unknown>;
  elasticsearch?: ElasticHealth;
  [key: string]: unknown;
};

export type SessionLinkType = "spawned" | "steered" | "continued";

export type SessionLinkPeer = {
  id: string;
  title: string;
  firstHumanTurn?: string | null;
  source: string;
};

export type SessionLink = {
  linkId: string;
  parentSessionId: string;
  childSessionId: string;
  linkType: SessionLinkType;
  createdAt: string;
  session: SessionLinkPeer;
};

export type SessionLinksResponse = {
  parents: SessionLink[];
  children: SessionLink[];
};

export type CreateSessionLinkRequest = {
  parentSessionId: string;
  childSessionId: string;
  linkType: SessionLinkType;
};

export type DagNodeType = "session";
export type DagEdgeType = "spawned" | "steered" | "continued";

export type DagNode = {
  id: string;
  type: DagNodeType;
  label: string;
  status?: string | null;
  ref: string;
};

export type DagEdge = {
  from: string;
  to: string;
  type: DagEdgeType;
};

export type DagResponse = {
  nodes: DagNode[];
  edges: DagEdge[];
};

export class ApiError extends Error {
  readonly status: number;
  readonly type?: string;
  readonly payload?: unknown;

  constructor(message: string, status: number, type?: string, payload?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.type = type;
    this.payload = payload;
  }
}

export function getSessions(
  limit = 250,
  includeChildren = false,
  humanOnly = false,
): Promise<AgentSession[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (includeChildren) params.set("includeChildren", "true");
  if (humanOnly) params.set("humanOnly", "true");
  return getJson(`/api/sessions?${params.toString()}`);
}

export function getSession(id: string): Promise<AgentSession> {
  return getJson(`/api/sessions/${encodeURIComponent(id)}`);
}

export function getEvent(id: string): Promise<AgentEvent> {
  return getJson(`/api/events/${encodeURIComponent(id)}`);
}

export function getSessionEvents(
  id: string,
  limit = 2_000,
  humanOnly = false,
): Promise<AgentEvent[]> {
  return getJson(
    `/api/sessions/${encodeURIComponent(id)}/events?limit=${encodeURIComponent(limit)}${
      humanOnly ? "&humanOnly=true" : ""
    }`,
  );
}

export function getSessionTranscript(
  id: string,
  params: SessionTranscriptParams = {},
): Promise<SessionTranscriptResponse> {
  const query = new URLSearchParams({ limit: String(params.limit ?? 100) });
  if (params.before) query.set("before", params.before);
  if (params.q?.trim()) query.set("q", params.q.trim());
  if (params.humanOnly) query.set("humanOnly", "true");
  return getJson(`/api/sessions/${encodeURIComponent(id)}/transcript?${query.toString()}`);
}

export function search(q: string, limit = 80, humanOnly = false): Promise<SearchResponse> {
  return getJson(
    `/api/search?q=${encodeURIComponent(q)}&limit=${encodeURIComponent(limit)}${
      humanOnly ? "&humanOnly=true" : ""
    }`,
  );
}

export function getEventFeed(params: EventFeedParams = {}): Promise<EventFeedResponse> {
  const query = new URLSearchParams();
  if (params.q?.trim()) query.set("q", params.q.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.before) query.set("before", params.before);
  if (params.since) query.set("since", params.since);
  if (params.meaningful !== undefined) query.set("meaningful", String(params.meaningful));
  if (params.humanOnly) query.set("humanOnly", "true");
  const suffix = query.toString();
  return getJson(`/api/events${suffix ? `?${suffix}` : ""}`);
}

export function getEventFacets(
  params: { q?: string; meaningful?: boolean; humanOnly?: boolean } = {},
  signal?: AbortSignal,
): Promise<EventFacetCounts> {
  const query = new URLSearchParams();
  if (params.q?.trim()) query.set("q", params.q.trim());
  if (params.meaningful !== undefined) query.set("meaningful", String(params.meaningful));
  if (params.humanOnly) query.set("humanOnly", "true");
  const suffix = query.toString();
  return getJson(`/api/events/facets${suffix ? `?${suffix}` : ""}`, signal);
}

export type RecallQuery = {
  project?: string;
  query: string;
  includeSuperseded?: boolean;
};

export function getRecall(
  scope: string | RecallQuery,
  withinHours: number,
  kinds: string[],
  includeSuperseded = false,
): Promise<RecallResult> {
  const params = new URLSearchParams({
    withinHours: String(withinHours),
  });
  if (typeof scope === "string") {
    if (scope.trim()) params.set("scope", scope.trim());
    if (includeSuperseded) params.set("includeSuperseded", "true");
  } else {
    if (scope.project?.trim()) params.set("project", scope.project.trim());
    params.set("query", scope.query.trim());
    params.set("includeSuperseded", String(scope.includeSuperseded ?? false));
  }
  if (kinds.length) params.set("kinds", kinds.join(","));
  return getJson(`/api/recall?${params.toString()}`);
}

export type CaptureDecisionRequest = {
  source: string;
  clientSessionId: string;
  repo: string;
  decision: string;
  rationale: string;
  supersedes?: string;
};

export function captureDecision(request: CaptureDecisionRequest): Promise<IngestResponse> {
  return postJson("/api/decisions", request);
}

export function getIdeas(params: IdeaListParams = {}): Promise<IdeaListResponse> {
  const query = new URLSearchParams();
  const statuses = (params.status ?? []).map((value) => value.trim()).filter(Boolean);
  if (statuses.length) query.set("status", statuses.join(","));
  if (params.origin?.trim()) query.set("origin", params.origin.trim());
  if (params.project?.trim()) query.set("project", params.project.trim());
  if (params.q?.trim()) query.set("q", params.q.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  const suffix = query.toString();
  return getJson(`/api/ideas${suffix ? `?${suffix}` : ""}`);
}

export function captureIdea(request: CaptureIdeaRequest): Promise<IngestResponse> {
  return postJson("/api/ideas", request);
}

// Dry run only: the migration endpoint writes nothing unless apply=true, which the UI never sends.
export function previewIdeaMigration(): Promise<IdeaMigrationResult> {
  return postJson("/api/ideas/migrate-observations?apply=false", {});
}

export function getProjects(): Promise<ProjectSummary[]> {
  return getJson("/api/projects");
}

export function getCodeProjectScopes(): Promise<CodeProjectScope[]> {
  return getJson("/api/projects/code-scopes");
}

export function openInEditor(reference: CodeReference): Promise<CodeNavigationResult> {
  return postJson("/api/open-in-editor", reference);
}

export function revealInFinder(reference: CodeReference): Promise<CodeNavigationResult> {
  return postJson("/api/reveal-in-finder", reference);
}

export function mergeProjectAlias(aliasKey: string, canonicalKey: string): Promise<ProjectAlias> {
  return putJson("/api/project-aliases", { aliasKey, canonicalKey });
}

export async function deleteProjectAlias(aliasKey: string): Promise<void> {
  const query = new URLSearchParams({ aliasKey });
  const response = await apiRequest(`/api/project-aliases?${query.toString()}`, {
    method: "DELETE",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) await readJson<unknown>(response);
}

export function getProjectSessions(key: string, limit = 250): Promise<AgentSession[]> {
  return getJson(
    `/api/projects/${encodeURIComponent(key)}/sessions?limit=${encodeURIComponent(limit)}`,
  );
}

export function getProjectTimeline(
  key: string,
  limit = 250,
  offset = 0,
): Promise<ProjectTimelineResponse> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return getJson(`/api/projects/${encodeURIComponent(key)}/timeline?${params.toString()}`);
}

export function getProjectTrajectory(key: string): Promise<ProjectTrajectoryResponse> {
  return getJson(`/api/projects/${encodeURIComponent(key)}/graph`);
}

export function getProjectMelds(key: string): Promise<ProjectMeld[]> {
  return getJson(`/api/projects/${encodeURIComponent(key)}/melds`);
}

export function previewProjectMeld(
  key: string,
  sessionIds: string[],
): Promise<ProjectMeldPreviewResponse> {
  return postJson(`/api/projects/${encodeURIComponent(key)}/melds/preview`, { sessionIds });
}

export function saveProjectMeld(request: ProjectMeldSaveRequest): Promise<ProjectSavedMeld> {
  return postJson("/api/melds", request);
}

export function searchFields(): Promise<FieldInfo[]> {
  return getJson("/api/search/fields");
}

export function searchValues(field: string, prefix = "", limit = 20): Promise<string[]> {
  const params = new URLSearchParams({ field, prefix, limit: String(limit) });
  return getJson(`/api/search/values?${params.toString()}`);
}

export function askStatus(): Promise<AskStatus> {
  return getJson("/api/ask/status");
}

export function ask(question: string, limit?: number): Promise<AskResponse> {
  return postJson("/api/ask", { question, limit });
}

export function getStatus(): Promise<ApiStatus> {
  return getJson("/api/status");
}

export function createSessionLink(request: CreateSessionLinkRequest): Promise<SessionLink> {
  return postJson("/api/session-links", request);
}

export function getSessionLinks(sessionId: string): Promise<SessionLinksResponse> {
  return getJson(`/api/sessions/${encodeURIComponent(sessionId)}/links`);
}

const CHILD_COUNT_BATCH_SIZE = 100;

// Tomcat rejects an oversized request line (default 8KB) before the app ever sees it, and the
// rail can carry up to 250 session ids — well past that limit as one `?ids=` query string.
// Chunk into batches of 100 (well under the limit) and merge; ≤100 ids stays a single request.
export async function getSessionChildCounts(ids: string[]): Promise<Record<string, number>> {
  if (!ids.length) return Promise.resolve({});
  const batches: string[][] = [];
  for (let index = 0; index < ids.length; index += CHILD_COUNT_BATCH_SIZE) {
    batches.push(ids.slice(index, index + CHILD_COUNT_BATCH_SIZE));
  }
  const results = await Promise.all(
    batches.map((batch) =>
      getJson<Record<string, number>>(
        `/api/session-links/child-counts?ids=${batch.map(encodeURIComponent).join(",")}`,
      ),
    ),
  );
  return results.reduce<Record<string, number>>((merged, batch) => ({ ...merged, ...batch }), {});
}

export function getSessionDag(sessionId: string): Promise<DagResponse> {
  return getJson(`/api/dag?sessionId=${encodeURIComponent(sessionId)}`);
}

// The login page and authenticated GETs refresh this cookie after session/CSRF rotation.
// HttpOnly session credentials stay in the browser; only the CSRF token is readable here.
function apiRequest(path: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = { ...init.headers } as Record<string, string>;
  if (!["GET", "HEAD", "OPTIONS", "TRACE"].includes(method) && typeof document !== "undefined") {
    const cookie = document.cookie
      .split(";")
      .map((part) => part.trim())
      .find((part) => part.startsWith("XSRF-TOKEN="));
    if (cookie) headers["X-XSRF-TOKEN"] = decodeURIComponent(cookie.slice("XSRF-TOKEN=".length));
  }
  return fetch(path, { ...init, headers }); // Fetch defaults to same-origin cookies.
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await apiRequest(path, { headers: { Accept: "application/json" }, signal });
  return readJson<T>(response);
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await apiRequest(path, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return readJson<T>(response);
}

async function putJson<T>(path: string, body: unknown): Promise<T> {
  const response = await apiRequest(path, {
    method: "PUT",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return readJson<T>(response);
}

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    let payload: unknown;
    let errorType: string | undefined;
    const body = await response.text().catch(() => "");
    try {
      payload = body ? JSON.parse(body) : undefined;
      const errorBody = payload as {
        message?: string;
        error?: string | { message?: string; type?: string };
      };
      const nestedError =
        typeof errorBody.error === "object" ? errorBody.error?.message : errorBody.error;
      errorType = typeof errorBody.error === "object" ? errorBody.error?.type : undefined;
      detail = errorBody.message || nestedError || detail;
    } catch {
      if (body) detail = body;
    }
    throw new ApiError(detail, response.status, errorType, payload);
  }
  return (await response.json()) as T;
}
