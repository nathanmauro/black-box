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

export function getSessions(limit = 250): Promise<AgentSession[]> {
  return getJson(`/api/sessions?limit=${encodeURIComponent(limit)}`);
}

export function getSessionEvents(id: string, limit = 2_000): Promise<AgentEvent[]> {
  return getJson(`/api/sessions/${encodeURIComponent(id)}/events?limit=${encodeURIComponent(limit)}`);
}

export function search(q: string, limit = 80): Promise<SearchResponse> {
  return getJson(`/api/search?q=${encodeURIComponent(q)}&limit=${encodeURIComponent(limit)}`);
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
      const payload = (await response.json()) as { message?: string; error?: string };
      detail = payload.message || payload.error || detail;
    } catch {
      const text = await response.text().catch(() => "");
      if (text) detail = text;
    }
    throw new Error(detail);
  }
  return (await response.json()) as T;
}
