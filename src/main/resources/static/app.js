const state = {
  sessions: [],
  activeSessionId: null
};

const els = {
  statusStrip: document.querySelector("#statusStrip"),
  sessions: document.querySelector("#sessions"),
  events: document.querySelector("#events"),
  sessionCount: document.querySelector("#sessionCount"),
  timelineTitle: document.querySelector("#timelineTitle"),
  timelineMeta: document.querySelector("#timelineMeta"),
  summarizeButton: document.querySelector("#summarizeButton"),
  summary: document.querySelector("#summary"),
  searchResults: document.querySelector("#searchResults"),
  searchMeta: document.querySelector("#searchMeta"),
  captureForm: document.querySelector("#captureForm"),
  searchForm: document.querySelector("#searchForm"),
  refreshButton: document.querySelector("#refreshButton")
};

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return response.json();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function relativeTime(value) {
  const date = new Date(value);
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

function statusPill(name, status) {
  const enabled = status?.enabled !== false;
  const ok = status?.available === true || (name === "storage" && status?.events !== undefined);
  const cls = ok ? "ok" : enabled ? "bad" : "off";
  const detail = name === "storage"
    ? `${status.sessions} sessions / ${status.events} events`
    : enabled ? (ok ? "reachable" : "offline") : "disabled";
  return `<div class="status-pill ${cls}"><strong>${escapeHtml(name)}</strong><small>${escapeHtml(detail)}</small><span></span></div>`;
}

async function loadStatus() {
  const status = await api("/api/status");
  els.statusStrip.innerHTML = [
    statusPill("storage", status.storage),
    statusPill("local AI", status.localAi),
    statusPill("elastic", status.elasticsearch)
  ].join("");
}

async function loadSessions(selectFirst = false) {
  state.sessions = await api("/api/sessions?limit=40");
  els.sessionCount.textContent = `${state.sessions.length} recent`;
  if (!state.sessions.length) {
    els.sessions.innerHTML = `<p class="empty">No sessions captured yet.</p>`;
    els.events.innerHTML = `<p class="empty">Capture an event or connect a hook.</p>`;
    return;
  }
  els.sessions.innerHTML = state.sessions.map(session => `
    <button class="session-row ${session.id === state.activeSessionId ? "active" : ""}" type="button" data-id="${escapeHtml(session.id)}">
      <span class="session-title">${escapeHtml(session.title)}</span>
      <span class="session-meta">${escapeHtml(session.source)} · ${session.eventCount} events · ${relativeTime(session.lastSeenAt)}</span>
    </button>
  `).join("");
  if (selectFirst || !state.activeSessionId) {
    await selectSession(state.sessions[0].id);
  }
}

async function selectSession(sessionId) {
  state.activeSessionId = sessionId;
  const session = state.sessions.find(item => item.id === sessionId);
  if (session) {
    els.timelineTitle.textContent = session.title;
    els.timelineMeta.textContent = `${session.source} · ${session.clientSessionId} · ${session.eventCount} events`;
    els.summary.hidden = !session.summary;
    els.summary.textContent = session.summary || "";
  }
  els.summarizeButton.disabled = false;
  [...els.sessions.querySelectorAll(".session-row")].forEach(row => {
    row.classList.toggle("active", row.dataset.id === sessionId);
  });
  const events = await api(`/api/sessions/${encodeURIComponent(sessionId)}/events?limit=100`);
  renderEvents(events);
}

function renderEvents(events) {
  if (!events.length) {
    els.events.innerHTML = `<p class="empty">No events in this session.</p>`;
    return;
  }
  els.events.innerHTML = events.map(event => `
    <article class="event">
      <div class="event-head">
        <strong>${escapeHtml(event.eventType)}${event.toolName ? ` · ${escapeHtml(event.toolName)}` : ""}</strong>
        <span>${relativeTime(event.observedAt)}</span>
      </div>
      <pre>${escapeHtml(event.text || event.toolInputJson || event.toolOutputJson || JSON.stringify(event.metadata, null, 2))}</pre>
    </article>
  `).join("");
}

function renderSearch(results) {
  els.searchMeta.textContent = `${results.local.length} local · ${results.elastic.length} elastic`;
  const local = results.local.map(event => `
    <article class="result">
      <div class="result-head">
        <strong>${escapeHtml(event.source)} · ${escapeHtml(event.eventType)}</strong>
        <span>${relativeTime(event.observedAt)}</span>
      </div>
      <pre>${escapeHtml(event.text || event.toolName || event.clientSessionId)}</pre>
    </article>
  `);
  const elastic = results.elastic.map(hit => `
    <article class="result">
      <div class="result-head">
        <strong>elastic · ${escapeHtml(hit.id)}</strong>
        <span>score ${escapeHtml(hit.score)}</span>
      </div>
      <pre>${escapeHtml(JSON.stringify(hit.source, null, 2))}</pre>
    </article>
  `);
  els.searchResults.innerHTML = [...local, ...elastic].join("") || `<p class="empty">No matches.</p>`;
}

els.sessions.addEventListener("click", event => {
  const row = event.target.closest(".session-row");
  if (row) selectSession(row.dataset.id);
});

els.refreshButton.addEventListener("click", async () => {
  await Promise.all([loadStatus(), loadSessions(false)]);
});

els.captureForm.addEventListener("submit", async event => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget).entries());
  await api("/api/events", {
    method: "POST",
    body: JSON.stringify({
      source: data.source,
      clientSessionId: data.clientSessionId,
      eventType: data.eventType,
      role: "user",
      text: data.text,
      metadata: { title: data.text?.split("\n")[0] || data.eventType },
      observedAt: new Date().toISOString()
    })
  });
  event.currentTarget.reset();
  event.currentTarget.elements.source.value = "manual";
  event.currentTarget.elements.clientSessionId.value = "manual-session";
  event.currentTarget.elements.eventType.value = "ManualCapture";
  await Promise.all([loadStatus(), loadSessions(true)]);
});

els.searchForm.addEventListener("submit", async event => {
  event.preventDefault();
  const query = new FormData(event.currentTarget).get("query");
  if (!query) return;
  const results = await api(`/api/search?q=${encodeURIComponent(query)}&limit=25`);
  renderSearch(results);
});

els.summarizeButton.addEventListener("click", async () => {
  if (!state.activeSessionId) return;
  els.summarizeButton.disabled = true;
  els.summarizeButton.textContent = "Summarizing";
  const session = await api(`/api/sessions/${encodeURIComponent(state.activeSessionId)}/summarize`, { method: "POST" });
  els.summary.hidden = false;
  els.summary.textContent = session.summary || "";
  els.summarizeButton.textContent = "Summarize";
  els.summarizeButton.disabled = false;
  await loadSessions(false);
});

Promise.all([loadStatus(), loadSessions(true)]).catch(error => {
  els.statusStrip.innerHTML = `<div class="status-pill bad"><strong>startup</strong><small>${escapeHtml(error.message)}</small><span></span></div>`;
});
