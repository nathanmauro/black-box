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

CREATE TABLE IF NOT EXISTS project_aliases (
    id TEXT PRIMARY KEY,
    alias_key TEXT NOT NULL UNIQUE,
    canonical_key TEXT NOT NULL,
    source TEXT NOT NULL,
    created_at TEXT NOT NULL,
    CHECK (alias_key <> canonical_key)
);

CREATE INDEX IF NOT EXISTS idx_project_aliases_canonical
    ON project_aliases (canonical_key);

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

CREATE INDEX IF NOT EXISTS idx_agent_events_observed
    ON agent_events (observed_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_events_source_type
    ON agent_events (source, event_type, observed_at DESC);

CREATE TABLE IF NOT EXISTS session_melds (
    id TEXT PRIMARY KEY,
    project_key TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    execution_mode TEXT NOT NULL,
    saved_from_preview INTEGER NOT NULL,
    metadata_json TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS session_meld_inputs (
    meld_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    input_order INTEGER NOT NULL,
    included_summary INTEGER NOT NULL,
    metadata_json TEXT,
    PRIMARY KEY (meld_id, session_id)
);

CREATE TABLE IF NOT EXISTS specs (
    id TEXT PRIMARY KEY,
    project_key TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    spec_ref TEXT,
    status TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    spec_id TEXT NOT NULL,
    project_key TEXT NOT NULL,
    title TEXT NOT NULL,
    lane TEXT NOT NULL,
    status TEXT NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    created_by TEXT NOT NULL,
    claimed_by TEXT,
    blocked_reason TEXT,
    result_handoff_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (spec_id) REFERENCES specs(id)
);

CREATE INDEX IF NOT EXISTS idx_tasks_claimable
    ON tasks (lane, status, priority DESC, created_at ASC);

CREATE TABLE IF NOT EXISTS task_events (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    type TEXT NOT NULL,
    actor TEXT NOT NULL,
    from_status TEXT,
    to_status TEXT,
    detail_json TEXT,
    observed_at TEXT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES tasks(id)
);

CREATE INDEX IF NOT EXISTS idx_task_events_task
    ON task_events (task_id, observed_at DESC);
