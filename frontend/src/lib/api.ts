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

export function getSessions(limit = 250): Promise<AgentSession[]> {
  return getJson(`/api/sessions?limit=${encodeURIComponent(limit)}`);
}

export function getSessionEvents(id: string, limit = 2_000): Promise<AgentEvent[]> {
  return getJson(`/api/sessions/${encodeURIComponent(id)}/events?limit=${encodeURIComponent(limit)}`);
}

export function search(q: string, limit = 80): Promise<SearchResponse> {
  return getJson(`/api/search?q=${encodeURIComponent(q)}&limit=${encodeURIComponent(limit)}`);
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

export function getProjectSessions(key: string): Promise<AgentSession[]> {
  return getJson(`/api/projects/${encodeURIComponent(key)}/sessions`);
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

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const payload = (await response.json()) as { message?: string; error?: string | { message?: string } };
      const nestedError = typeof payload.error === "object" ? payload.error?.message : payload.error;
      detail = payload.message || nestedError || detail;
    } catch {
      const text = await response.text().catch(() => "");
      if (text) detail = text;
    }
    throw new Error(detail);
  }
  return (await response.json()) as T;
}
