export type AgentSession = {
  id: string;
  source: string;
  clientSessionId: string;
  title: string;
  cwd?: string | null;
  summary?: string | null;
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
  toolName?: string | null;
  toolInputJson?: string | null;
  toolOutputJson?: string | null;
  metadata?: unknown;
  observedAt: string;
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
};

export type RecallResult = {
  scope?: string | null;
  withinHours: number;
  kinds: string[];
  count: number;
  items: RecalledItem[];
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

export type DashboardBreakdown = {
  name: string;
  count: number;
};

export type DashboardDailyCount = {
  day: string;
  count: number;
};

export type DashboardStats = {
  totalSessions: number;
  totalEvents: number;
  eventsBySource: DashboardBreakdown[];
  eventsByKind: DashboardBreakdown[];
  sessionsBySource: DashboardBreakdown[];
  recentActivity: DashboardDailyCount[];
};

export type SpecStatus = "active" | "done" | "archived";

export type TaskStatus = "open" | "claimed" | "in_progress" | "blocked" | "done" | "cancelled";

export type TaskEventType =
  | "task.created"
  | "task.claimed"
  | "task.blocked"
  | "task.completed"
  | "task.reset"
  | "task.cancelled";

export type Spec = {
  id: string;
  projectKey: string;
  title: string;
  body: string;
  specRef?: Record<string, unknown> | null;
  status: SpecStatus;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

export type AgentTask = {
  id: string;
  specId: string;
  projectKey: string;
  title: string;
  lane: string;
  status: TaskStatus;
  priority: number;
  createdBy: string;
  claimedBy?: string | null;
  blockedReason?: string | null;
  resultHandoffId?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type TaskEvent = {
  id: string;
  taskId: string;
  type: TaskEventType;
  actor: string;
  fromStatus?: TaskStatus | null;
  toStatus?: TaskStatus | null;
  detail?: Record<string, unknown> | null;
  observedAt: string;
};

export type TaskSnapshot = {
  task: AgentTask;
  spec: Spec;
};

export type TaskChange = {
  snapshot: TaskSnapshot;
  event: TaskEvent;
};

export type CreateSpecRequest = {
  projectKey: string;
  title: string;
  body: string;
  specRef?: Record<string, unknown> | null;
  actor: string;
};

export type EnqueueTaskRequest = {
  specId: string;
  title: string;
  lane: string;
  priority: number;
  actor: string;
};

export type ClaimTaskRequest = {
  lane: string;
  agent: string;
};

export type UpdateTaskStatusRequest =
  | { actor: string; status: "blocked"; blockedReason: string }
  | { actor: string; status: "open" | "cancelled"; blockedReason?: never };

export type CompleteTaskRequest = {
  actor: string;
  source: string;
  clientSessionId: string;
  summary: string;
  openLoops: string[];
  nextAction: string;
};

export type TaskFilters = {
  projectKey?: string;
  lane?: string;
  status?: TaskStatus;
  limit?: number;
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

export function getSessions(limit = 250): Promise<AgentSession[]> {
  return getJson(`/api/sessions?limit=${encodeURIComponent(limit)}`);
}

export function getSessionEvents(id: string, limit = 2_000): Promise<AgentEvent[]> {
  return getJson(`/api/sessions/${encodeURIComponent(id)}/events?limit=${encodeURIComponent(limit)}`);
}

export function search(q: string, limit = 80): Promise<SearchResponse> {
  return getJson(`/api/search?q=${encodeURIComponent(q)}&limit=${encodeURIComponent(limit)}`);
}

export function getEventFeed(params: EventFeedParams = {}): Promise<EventFeedResponse> {
  const query = new URLSearchParams();
  if (params.q?.trim()) query.set("q", params.q.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.before) query.set("before", params.before);
  if (params.since) query.set("since", params.since);
  if (params.meaningful !== undefined) query.set("meaningful", String(params.meaningful));
  const suffix = query.toString();
  return getJson(`/api/events${suffix ? `?${suffix}` : ""}`);
}

export function getRecall(scope: string, withinHours: number, kinds: string[]): Promise<RecallResult> {
  const params = new URLSearchParams({
    withinHours: String(withinHours),
  });
  if (scope.trim()) params.set("scope", scope.trim());
  if (kinds.length) params.set("kinds", kinds.join(","));
  return getJson(`/api/recall?${params.toString()}`);
}

export function getProjects(): Promise<ProjectSummary[]> {
  return getJson("/api/projects");
}

export function getProjectSessions(key: string, limit = 250): Promise<AgentSession[]> {
  return getJson(`/api/projects/${encodeURIComponent(key)}/sessions?limit=${encodeURIComponent(limit)}`);
}

export function getProjectTimeline(key: string, limit = 250, offset = 0): Promise<ProjectTimelineResponse> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return getJson(`/api/projects/${encodeURIComponent(key)}/timeline?${params.toString()}`);
}

export function getProjectMelds(key: string): Promise<ProjectMeld[]> {
  return getJson(`/api/projects/${encodeURIComponent(key)}/melds`);
}

export function previewProjectMeld(key: string, sessionIds: string[]): Promise<ProjectMeldPreviewResponse> {
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

export function getDashboardStats(): Promise<DashboardStats> {
  return getJson("/api/stats");
}

export function createSpec(request: CreateSpecRequest): Promise<Spec> {
  return postJson("/api/specs", request);
}

export function getSpec(specId: string): Promise<Spec> {
  return getJson(`/api/specs/${encodeURIComponent(specId)}`);
}

export function enqueueTask(request: EnqueueTaskRequest): Promise<TaskChange> {
  return postJson("/api/tasks", request);
}

export async function claimNextTask(request: ClaimTaskRequest): Promise<TaskChange | null> {
  const response = await fetch("/api/tasks/claim", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (response.status === 204) return null;
  return readJson<TaskChange>(response);
}

export function updateTaskStatus(taskId: string, request: UpdateTaskStatusRequest): Promise<TaskChange> {
  return patchJson(`/api/tasks/${encodeURIComponent(taskId)}`, request);
}

export function completeTask(taskId: string, request: CompleteTaskRequest): Promise<TaskChange> {
  return postJson(`/api/tasks/${encodeURIComponent(taskId)}/complete`, request);
}

export function listTasks(filters: TaskFilters = {}): Promise<TaskSnapshot[]> {
  const query = new URLSearchParams();
  if (filters.projectKey !== undefined) query.set("projectKey", filters.projectKey);
  if (filters.lane !== undefined) query.set("lane", filters.lane);
  if (filters.status !== undefined) query.set("status", filters.status);
  if (filters.limit !== undefined) query.set("limit", String(filters.limit));
  const suffix = query.toString();
  return getJson(`/api/tasks${suffix ? `?${suffix}` : ""}`);
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: "application/json" } });
  return readJson<T>(response);
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return readJson<T>(response);
}

async function patchJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "PATCH",
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
      const errorBody = payload as { message?: string; error?: string | { message?: string; type?: string } };
      const nestedError = typeof errorBody.error === "object" ? errorBody.error?.message : errorBody.error;
      errorType = typeof errorBody.error === "object" ? errorBody.error?.type : undefined;
      detail = errorBody.message || nestedError || detail;
    } catch {
      if (body) detail = body;
    }
    throw new ApiError(detail, response.status, errorType, payload);
  }
  return (await response.json()) as T;
}
