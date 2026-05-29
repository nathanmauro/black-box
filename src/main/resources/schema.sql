CREATE TABLE IF NOT EXISTS agent_sessions (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    client_session_id TEXT NOT NULL,
    title TEXT NOT NULL,
    title_rank INTEGER NOT NULL DEFAULT 1,
    cwd TEXT,
    summary TEXT,
    started_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    event_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (source, client_session_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_sessions_last_seen
    ON agent_sessions (last_seen_at DESC);

CREATE TABLE IF NOT EXISTS agent_events (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    source TEXT NOT NULL,
    client_session_id TEXT NOT NULL,
    turn_id TEXT,
    event_type TEXT NOT NULL,
    role TEXT,
    text TEXT,
    tool_name TEXT,
    tool_input_json TEXT,
    tool_output_json TEXT,
    metadata_json TEXT,
    observed_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES agent_sessions(id)
);

CREATE INDEX IF NOT EXISTS idx_agent_events_session_observed
    ON agent_events (session_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_events_source_type
    ON agent_events (source, event_type, observed_at DESC);
