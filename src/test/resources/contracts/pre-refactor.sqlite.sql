-- Schema/data fixture captured before the Java package refactor. agent_sessions intentionally lacks
-- title_rank keeps the pre-refactor schema migration in the compatibility contract.
CREATE TABLE agent_sessions (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    client_session_id TEXT NOT NULL,
    title TEXT NOT NULL,
    cwd TEXT,
    summary TEXT,
    started_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    event_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (source, client_session_id)
);

CREATE TABLE agent_events (
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

CREATE TABLE specs (
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

CREATE TABLE tasks (
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

CREATE TABLE task_events (
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

INSERT INTO agent_sessions (
    id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
) VALUES (
    'legacy-session', 'codex', 'legacy-client', 'Legacy session', '/repo', 'Legacy summary',
    '2026-07-19T12:00:00Z', '2026-07-19T12:01:00Z', 1
);

INSERT INTO agent_events (
    id, session_id, source, client_session_id, turn_id, event_type, role, text, metadata_json, observed_at
) VALUES (
    'legacy-event', 'legacy-session', 'codex', 'legacy-client', 'turn-1', 'Handoff', 'assistant',
    'Legacy handoff', '{"kind":"handoff"}', '2026-07-19T12:01:00Z'
);

INSERT INTO specs (
    id, project_key, title, body, spec_ref, status, created_by, created_at, updated_at
) VALUES (
    'legacy-spec', '/repo', 'Legacy spec', 'Frozen legacy spec', '{"sha":"abc123"}', 'active',
    'planner', '2026-07-19T12:00:00Z', '2026-07-19T12:00:00Z'
);

INSERT INTO tasks (
    id, spec_id, project_key, title, lane, status, priority, created_by, created_at, updated_at
) VALUES (
    'legacy-task', 'legacy-spec', '/repo', 'Legacy task', 'codex', 'open', 5, 'planner',
    '2026-07-19T12:00:00Z', '2026-07-19T12:00:00Z'
);

INSERT INTO task_events (
    id, task_id, type, actor, from_status, to_status, detail_json, observed_at
) VALUES (
    'legacy-transition', 'legacy-task', 'task.created', 'planner', NULL, 'open', NULL,
    '2026-07-19T12:00:00Z'
);
