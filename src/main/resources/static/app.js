// Black Box — instrument logic. Reads the local recorder over /api/*, draws the Cognition Spine,
// and fires the recall beam. No framework, no build step. The screen is an instrument, not a page.

const state = {
  sessions: [],
  exportTargets: [],
  activeTab: "spine",
  activeSessionId: null,
  activeEvents: [],
  activeToolFilter: "all",
  spineEventIds: new Set(),
  expandedSessionGroups: new Set(),
  sessionGroupStateLoaded: false,
  askAvailable: false,
};

const SESSION_GROUP_STORAGE_KEY = "blackbox.sessions.expandedGroups.v1";
const NO_PROJECT_GROUP_KEY = "__no_project__";
const NO_PROJECT_GROUP_LABEL = "No project / manual / system";

const els = {
  readouts: document.querySelector("#readouts"),
  stageTabs: document.querySelector(".stage-tabs"),
  tabPanels: document.querySelectorAll("[data-tab-panel]"),
  sessions: document.querySelector("#sessions"),
  sessionCount: document.querySelector("#sessionCount"),
  refreshButton: document.querySelector("#refreshButton"),
  expandAllSessionsButton: document.querySelector("#expandAllSessionsButton"),
  collapseAllSessionsButton: document.querySelector("#collapseAllSessionsButton"),
  captureForm: document.querySelector("#captureForm"),
  askTab: document.querySelector("#tab-ask"),
  askPanel: document.querySelector("#panel-ask"),
  askAvailabilityNotice: document.querySelector("#askAvailabilityNotice"),
  askForm: document.querySelector("#askForm"),
  askMeta: document.querySelector("#askMeta"),
  askResults: document.querySelector("#askResults"),
  retrieveButton: document.querySelector("#retrieveButton"),
  searchForm: document.querySelector("#searchForm"),
  searchResults: document.querySelector("#searchResults"),
  searchMeta: document.querySelector("#searchMeta"),
  recallPanel: document.querySelector("#recallPanel"),
  recallBody: document.querySelector("#recallBody"),
  recallForm: document.querySelector("#recallForm"),
  recallMeta: document.querySelector("#recallMeta"),
  recallStage: document.querySelector("#recallStage"),
  constellation: document.querySelector("#constellation"),
  memoryDetail: document.querySelector("#memoryDetail"),
  clearRecallButton: document.querySelector("#clearRecallButton"),
  toggleRecallButton: document.querySelector("#toggleRecallButton"),
  spine: document.querySelector("#spine"),
  spineTitle: document.querySelector("#spineTitle"),
  spineMeta: document.querySelector("#spineMeta"),
  sessionIdentity: document.querySelector("#sessionIdentity"),
  copySessionButton: document.querySelector("#copySessionButton"),
  toolFilter: document.querySelector("#toolFilter"),
  toolFilterMeta: document.querySelector("#toolFilterMeta"),
  summary: document.querySelector("#summary"),
  summarizeButton: document.querySelector("#summarizeButton"),
  exportTargetSelect: document.querySelector("#exportTargetSelect"),
  exportSummaryButton: document.querySelector("#exportSummaryButton"),
  summaryExportStatus: document.querySelector("#summaryExportStatus"),
};

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.error?.message) detail = body.error.message;
    } catch (_) { /* non-JSON error body */ }
    throw new Error(detail);
  }
  return response.json();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#039;");
}

function relativeTime(value) {
  if (!value) return "";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

function firstLine(value) {
  if (!value) return "";
  const nl = value.indexOf("\n");
  return nl >= 0 ? value.slice(0, nl) : value;
}

function num(value) {
  return Number(value).toLocaleString("en-US");
}

// --------------------------------------------------------------------- tabs
function activateTab(tab) {
  const nextTab = tab === "ask" && !state.askAvailable ? "spine" : tab;
  state.activeTab = nextTab;
  els.stageTabs.querySelectorAll("[data-tab]").forEach(button => {
    const active = button.dataset.tab === nextTab;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", String(active));
  });
  els.tabPanels.forEach(panel => {
    const active = panel.dataset.tabPanel === nextTab;
    panel.classList.toggle("active", active);
    panel.hidden = !active;
  });
  if (nextTab === "recall" && !els.recallStage.hidden) {
    requestAnimationFrame(() => window.BlackBoxConstellation?.redraw());
  }
}

// ------------------------------------------------------------------ readouts
function gauge(label, cls, value) {
  return `<div class="gauge ${cls}"><span class="gauge-dot"></span>` +
    `<span class="gauge-label">${escapeHtml(label)}</span>` +
    `<span class="gauge-value">${escapeHtml(value)}</span></div>`;
}

async function loadStatus(pulse = false) {
  const status = await api("/api/status");
  const storage = status.storage || {};
  const ai = status.localAi || {};
  const es = status.elasticsearch || {};

  // Storage is "ok" because the store answered this very request — no faked green.
  const storageGauge = gauge("storage", "ok", `${num(storage.events ?? 0)} ev · ${num(storage.sessions ?? 0)} ses`);
  els.readouts.innerHTML = [
    storageGauge,
    serviceGauge("local-ai", ai, ai.model || "online"),
    serviceGauge("elastic", es, es.indexName || "online"),
  ].join("");

  if (pulse) {
    const g = els.readouts.querySelector(".gauge");
    if (g) { g.classList.remove("capturing"); void g.offsetWidth; g.classList.add("capturing"); }
  }
}

async function loadAskStatus() {
  try {
    const status = await api("/api/ask/status");
    const available = status.elasticsearch?.available === true;
    setAskAvailability(available);
    if (!available) return;
    const mode = status.retrievalMode || "unavailable";
    const chat = status.chat?.available ? "chat ok" : "chat degraded";
    els.askMeta.textContent = `${mode} · ${status.memoryIndex || "agent-memory"} · ${chat}`;
  } catch (_) {
    setAskAvailability(false);
  }
}

function setAskAvailability(available) {
  state.askAvailable = available;
  els.askTab.hidden = !available;
  els.askTab.setAttribute("aria-hidden", String(!available));
  els.askAvailabilityNotice.hidden = available;
  if (!available) {
    els.askMeta.textContent = "";
    els.askPanel.classList.remove("active");
    els.askPanel.hidden = true;
    if (state.activeTab === "ask") activateTab("spine");
    return;
  }
  els.askTab.removeAttribute("aria-hidden");
  activateTab(state.activeTab);
}

async function loadExportTargets() {
  state.exportTargets = await api("/api/exports/targets");
  if (!state.exportTargets.length) {
    els.exportTargetSelect.innerHTML = `<option value="">No export targets</option>`;
    els.exportTargetSelect.disabled = true;
    els.exportSummaryButton.disabled = true;
    els.exportSummaryButton.textContent = "Export";
    return;
  }
  els.exportTargetSelect.innerHTML = state.exportTargets
    .map(target => `<option value="${escapeHtml(target.id)}">${escapeHtml(target.label || target.id)}</option>`)
    .join("");
  els.exportTargetSelect.disabled = false;
  els.exportSummaryButton.textContent = "Export";
}

// A real three-state readout: ok (reachable), degraded (enabled but unreachable), off (disabled).
function serviceGauge(label, health, okValue) {
  if (health.enabled === false) return gauge(label, "off", "off");
  if (health.available === true) return gauge(label, "ok", okValue);
  return gauge(label, "degraded", "offline");
}

// ------------------------------------------------------------------ sessions
async function loadSessions(selectFirst = false) {
  state.sessions = await api("/api/sessions?limit=40");
  els.sessionCount.textContent = `${state.sessions.length}`;
  if (!state.sessions.length) {
    state.activeEvents = [];
    els.copySessionButton.disabled = true;
    els.exportTargetSelect.disabled = true;
    els.exportSummaryButton.disabled = true;
    els.sessionIdentity.innerHTML = "";
    els.toolFilter.innerHTML = "";
    els.toolFilter.setAttribute("aria-disabled", "true");
    els.toolFilterMeta.textContent = "";
    els.sessions.innerHTML = `<p class="empty">No sessions yet. Run ./scripts/demo.sh or connect a hook.</p>`;
    els.spine.innerHTML = `<p class="empty">The recorder is idle. Capture an event to lay down the first trace.</p>`;
    return;
  }
  const groups = groupSessionsByProject(state.sessions);
  loadExpandedSessionGroups(groups);
  const shouldSelect = selectFirst || !state.activeSessionId || !state.sessions.some(s => s.id === state.activeSessionId);
  const selectedSessionId = shouldSelect ? state.sessions[0].id : state.activeSessionId;
  ensureSessionGroupExpanded(selectedSessionId, false);
  renderSessionsRail(groups);
  if (shouldSelect) {
    await selectSession(selectedSessionId, { renderRail: false });
    renderSessionsRail();
  }
}

function renderSessionsRail(groups = groupSessionsByProject(state.sessions)) {
  els.sessions.innerHTML = groups.map(renderSessionGroup).join("");
}

function renderSessionGroup(group) {
  const expanded = state.expandedSessionGroups.has(group.key);
  const active = group.sessions.some(session => session.id === state.activeSessionId) ? "active" : "";
  const rows = group.sessions.map(renderSessionRow).join("");
  return `<section class="session-group ${active}" data-project-key="${escapeHtml(group.key)}">
    <button type="button" class="session-group-toggle" data-project-key="${escapeHtml(group.key)}" aria-expanded="${String(expanded)}">
      <span class="session-group-caret">${expanded ? "▾" : "▸"}</span>
      <span class="session-group-title">${escapeHtml(group.label)}</span>
      <span class="session-group-count">${num(group.sessions.length)}</span>
    </button>
    <div class="session-group-sessions" ${expanded ? "" : "hidden"}>${rows}</div>
  </section>`;
}

function renderSessionRow(session) {
  const src = (session.source || "unknown").toLowerCase();
  const aiTitled = session.summary ? `<span class="seal">◆ ai</span>` : "";
  const active = session.id === state.activeSessionId ? "active" : "";
  return `<div class="channel-row ${active}" data-id="${escapeHtml(session.id)}">
      <button class="channel ${active}" data-id="${escapeHtml(session.id)}">
        <span class="channel-title">${escapeHtml(session.title)} ${aiTitled}</span>
        <span class="channel-meta">
          <span class="src src--${escapeHtml(src)}">${escapeHtml(src)}</span>
          <span>${num(session.eventCount)} ev</span>
          <span>·</span>
          <span>${escapeHtml(relativeTime(session.lastSeenAt))}</span>
        </span>
      </button>
      <button class="copy-button" data-session-id="${escapeHtml(session.id)}" title="Copy session IDs" aria-label="Copy session IDs">⧉</button>
    </div>`;
}

function groupSessionsByProject(sessions) {
  const groupsByKey = new Map();
  for (const session of sessions) {
    const key = sessionProjectGroupKey(session);
    if (!groupsByKey.has(key)) {
      groupsByKey.set(key, {
        key,
        label: key === NO_PROJECT_GROUP_KEY ? NO_PROJECT_GROUP_LABEL : key,
        noProject: key === NO_PROJECT_GROUP_KEY,
        sessions: [],
      });
    }
    groupsByKey.get(key).sessions.push(session);
  }
  const groups = [...groupsByKey.values()];
  return [
    ...groups.filter(group => !group.noProject),
    ...groups.filter(group => group.noProject),
  ];
}

function sessionProjectGroupKey(session) {
  return formatProjectPath(session?.cwd) || NO_PROJECT_GROUP_KEY;
}

const HOME_DIR_PATTERN = /^(\/Users\/[^/]+|\/home\/[^/]+)(?=\/|$)/;

function formatProjectPath(path) {
  const value = String(path || "").trim();
  if (!value) return "";
  const match = value.match(HOME_DIR_PATTERN);
  if (!match) return value;
  const rest = value.slice(match[1].length);
  return rest ? `~${rest}` : "~";
}

function loadExpandedSessionGroups(groups) {
  if (state.sessionGroupStateLoaded) return;
  state.sessionGroupStateLoaded = true;
  try {
    const raw = window.localStorage?.getItem(SESSION_GROUP_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        state.expandedSessionGroups = new Set(parsed.filter(item => typeof item === "string"));
        return;
      }
    }
  } catch (_) {
    state.expandedSessionGroups = new Set();
  }
  state.expandedSessionGroups = new Set(groups.map(group => group.key));
  saveExpandedSessionGroups();
}

function saveExpandedSessionGroups() {
  try {
    window.localStorage?.setItem(
      SESSION_GROUP_STORAGE_KEY,
      JSON.stringify([...state.expandedSessionGroups].sort())
    );
  } catch (_) { /* localStorage may be unavailable in private or file contexts */ }
}

function ensureSessionGroupExpanded(sessionId, persist = true) {
  const session = state.sessions.find(item => item.id === sessionId);
  if (!session) return;
  const key = sessionProjectGroupKey(session);
  if (state.expandedSessionGroups.has(key)) return;
  state.expandedSessionGroups.add(key);
  if (persist) saveExpandedSessionGroups();
}

function expandAllSessionGroups() {
  for (const group of groupSessionsByProject(state.sessions)) {
    state.expandedSessionGroups.add(group.key);
  }
  saveExpandedSessionGroups();
  renderSessionsRail();
}

function collapseAllSessionGroups() {
  state.expandedSessionGroups.clear();
  ensureSessionGroupExpanded(state.activeSessionId, false);
  saveExpandedSessionGroups();
  renderSessionsRail();
}

async function selectSession(sessionId, options = {}) {
  const { renderRail = true } = options;
  state.activeSessionId = sessionId;
  ensureSessionGroupExpanded(sessionId);
  const session = state.sessions.find(s => s.id === sessionId);
  if (session) {
    els.spineTitle.textContent = session.title;
    const where = session.cwd ? ` · ${session.cwd}` : "";
    els.spineMeta.textContent = `${session.source}${where} · ${session.eventCount} events`;
    els.sessionIdentity.innerHTML = renderSessionIdentity(session);
    els.copySessionButton.disabled = false;
    els.summary.hidden = !session.summary;
    els.summary.textContent = session.summary || "";
    setSummaryExportState(session);
  }
  els.summarizeButton.disabled = false;
  if (renderRail) renderSessionsRail();
  els.sessions.querySelectorAll(".channel").forEach(row =>
    row.classList.toggle("active", row.dataset.id === sessionId));
  els.sessions.querySelectorAll(".channel-row").forEach(row =>
    row.classList.toggle("active", row.dataset.id === sessionId));
  const events = await api(`/api/sessions/${encodeURIComponent(sessionId)}/events?limit=${eventLimitFor(session)}`);
  state.activeEvents = events;
  refreshToolFilter(events);
  renderFilteredSpine();
}

function setSummaryExportState(session) {
  const hasTargets = state.exportTargets.length > 0;
  const hasSummary = !!session?.summary;
  els.exportTargetSelect.disabled = !hasSummary || !hasTargets;
  els.exportSummaryButton.disabled = !hasSummary || !hasTargets;
  els.summaryExportStatus.hidden = true;
  els.summaryExportStatus.textContent = "";
  els.summaryExportStatus.classList.remove("error");
}

function eventLimitFor(session) {
  const count = Number(session?.eventCount);
  if (!Number.isFinite(count)) return 250;
  return Math.max(250, Math.min(count, 2000));
}

function renderSessionIdentity(session) {
  return [
    idChip("blackbox", session.id),
    idChip("client", session.clientSessionId),
  ].join("");
}

function idChip(label, value) {
  if (!value) return "";
  return `<button type="button" class="id-chip" data-copy-value="${escapeHtml(value)}" title="Copy ${escapeHtml(label)} session ID" aria-label="Copy ${escapeHtml(label)} session ID">
    <span>${escapeHtml(label)}</span>
    <code>${escapeHtml(compactId(value))}</code>
    <span class="copy-symbol">⧉</span>
  </button>`;
}

function compactId(value) {
  const text = String(value ?? "");
  if (text.length <= 20) return text;
  return `${text.slice(0, 8)}…${text.slice(-6)}`;
}

// ---------------------------------------------------------------- the spine
function classify(event) {
  const type = (event.eventType || "").toLowerCase();
  const kind = (event.metadata?.kind || "").toLowerCase();
  if (kind === "decision" || type === "decision") return "decision";
  if (kind === "handoff" || type === "handoff") return "handoff";
  if (/error|fail|denied/.test(type)) return "error";
  if (event.toolName || /tool/.test(type)) return "tool";
  return "prompt";
}

function dotSize(event) {
  const len = (event.text || "").length + (event.toolInputJson || "").length;
  if (len > 1200) return 16;
  if (len > 400) return 13;
  if (len > 40) return 11;
  return 9;
}

function renderSpine(events) {
  state.spineEventIds = new Set(events.map(e => e.id));
  if (!events.length) {
    els.spine.innerHTML = `<p class="empty">No events match this filter.</p>`;
    return;
  }
  // Oldest-first reads like a trace laid down over time; the API returns newest-first.
  const ordered = [...events].reverse();
  els.spine.innerHTML = ordered.map((event, i) => renderNode(event, i)).join("");
}

function refreshToolFilter(events) {
  const counts = toolCounts(events);
  const previous = state.activeToolFilter;
  const options = [
    { value: "all", label: "All", count: events.length },
    { value: "tools", label: "Tool uses", count: toolEventCount(events) },
    ...counts.map(([tool, count]) => ({ value: `tool:${tool}`, label: tool, count })),
  ];
  els.toolFilter.innerHTML = options
    .map(renderFilterButton)
    .join("");
  const values = new Set(options.map(option => option.value));
  state.activeToolFilter = values.has(previous) ? previous : "all";
  els.toolFilter.setAttribute("aria-disabled", String(!events.length));
  updateFilterButtons();
}

function renderFilterButton(option) {
  return `<button type="button" class="filter-chip" data-filter="${escapeHtml(option.value)}" aria-pressed="false">
    <span>${escapeHtml(option.label)}</span>
    <strong>${escapeHtml(num(option.count))}</strong>
  </button>`;
}

function updateFilterButtons() {
  els.toolFilter.querySelectorAll(".filter-chip").forEach(button => {
    const active = button.dataset.filter === state.activeToolFilter;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
}

function toolCounts(events) {
  const counts = new Map();
  for (const event of events) {
    if (!event.toolName) continue;
    counts.set(event.toolName, (counts.get(event.toolName) || 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
}

function toolEventCount(events) {
  return events.filter(isToolEvent).length;
}

function isToolEvent(event) {
  return Boolean(event.toolName) || /tool/i.test(event.eventType || "");
}

function renderFilteredSpine() {
  const filtered = filterEventsByTool(state.activeEvents);
  updateFilterButtons();
  renderSpine(filtered);
  if (!state.activeEvents.length) {
    els.toolFilterMeta.textContent = "";
  } else if (state.activeToolFilter === "all") {
    els.toolFilterMeta.textContent = `${num(state.activeEvents.length)} events`;
  } else {
    els.toolFilterMeta.textContent = `${num(filtered.length)} / ${num(state.activeEvents.length)} shown`;
  }
}

function filterEventsByTool(events) {
  if (state.activeToolFilter === "all") return events;
  if (state.activeToolFilter === "tools") return events.filter(isToolEvent);
  if (state.activeToolFilter.startsWith("tool:")) {
    const tool = state.activeToolFilter.slice("tool:".length);
    return events.filter(event => event.toolName === tool);
  }
  return events;
}

function renderNode(event, i) {
  const cls = classify(event);
  const meta = event.metadata || {};
  const time = relativeTime(event.observedAt);
  const head = (typeLabel, extra = "") =>
    `<div class="node-head"><span class="node-type">${escapeHtml(typeLabel)}</span>${extra}<span class="node-time">${escapeHtml(time)}</span></div>`;

  let body = "";
  if (cls === "decision") {
    const headline = escapeHtml(meta.decision || firstLine(event.text) || "Decision");
    const rationale = meta.rationale ? `<div class="mem-rationale" style="color:var(--muted);font-size:13px">${escapeHtml(meta.rationale)}</div>` : "";
    const loops = loopList(meta.openLoops);
    const conf = (meta.confidence != null) ? `<span>confidence <b>${escapeHtml(formatConf(meta.confidence))}</b></span>` : "";
    const alts = altsDetails(meta.alternatives);
    body = head("Decision", srcTick(event.source)) +
      `<div class="node-text">${headline}</div>${rationale}${loops}` +
      (conf ? `<div class="node-meta-line">${conf}</div>` : "") + alts;
  } else if (cls === "handoff") {
    const headline = escapeHtml(meta.contextSummary || firstLine(event.text) || "Handoff");
    const loops = loopList(meta.openLoops);
    const to = meta.toAgent ? `<span>→ <b>${escapeHtml(meta.toAgent)}</b></span>` : "";
    const next = meta.nextAction ? `<div class="node-meta-line">next: ${escapeHtml(meta.nextAction)}</div>` : "";
    body = head("Handoff", srcTick(event.source)) +
      `<div class="node-text">${headline}</div>${loops}` +
      (to ? `<div class="node-meta-line">${to}</div>` : "") + next;
  } else if (cls === "tool") {
    const toolName = event.toolName ? `<span class="node-tool">${escapeHtml(event.toolName)}</span>` : "";
    const text = event.text ? `<div class="node-text">${escapeHtml(clamp(event.text, 400))}</div>` : "";
    body = head(event.eventType || "Tool", toolName + srcTick(event.source)) + text + ioDetails(event);
  } else if (cls === "error") {
    body = head(event.eventType || "Error", srcTick(event.source)) +
      `<div class="node-text">${escapeHtml(clamp(event.text || event.toolOutputJson || "Failure", 600))}</div>` + ioDetails(event);
  } else {
    body = head(event.eventType || "Event", srcTick(event.source)) +
      `<div class="node-text">${escapeHtml(clamp(event.text || event.toolInputJson || "", 800))}</div>`;
  }

  return `<article class="node node--${cls}" data-event-id="${escapeHtml(event.id)}" style="--i:${i}">
    <div class="node-rail"><span class="node-dot" style="--dot:${dotSize(event)}px"></span></div>
    <div class="node-body">${body}</div>
  </article>`;
}

function srcTick(source) {
  const s = (source || "unknown").toLowerCase();
  return `<span class="src src--${escapeHtml(s)}" style="font-size:9px">${escapeHtml(s)}</span>`;
}

function loopList(loops) {
  if (!Array.isArray(loops) || !loops.length) return "";
  return `<div class="node-loops">` +
    loops.map(l => `<div class="loop">${escapeHtml(l)}</div>`).join("") + `</div>`;
}

function altsDetails(alts) {
  if (!Array.isArray(alts) || !alts.length) return "";
  return `<details><summary>considered ${alts.length}</summary><pre>${escapeHtml(alts.join("\n"))}</pre></details>`;
}

function ioDetails(event) {
  const parts = [];
  if (event.toolInputJson) parts.push("input  " + event.toolInputJson);
  if (event.toolOutputJson) parts.push("output " + event.toolOutputJson);
  if (!parts.length) return "";
  return `<details><summary>tool i/o</summary><pre>${escapeHtml(clamp(parts.join("\n\n"), 4000))}</pre></details>`;
}

function clamp(value, max) {
  const v = String(value ?? "");
  return v.length <= max ? v : v.slice(0, max) + " …";
}

function formatConf(c) {
  const n = Number(c);
  return Number.isFinite(n) ? n.toFixed(2) : String(c);
}

function highlightText(highlight, field, fallback, max = 320) {
  const fragments = highlight?.[field];
  if (Array.isArray(fragments) && fragments.length) {
    return sanitizeHighlight(clamp(fragments.join(" … "), max));
  }
  return escapeHtml(clamp(fallback, max));
}

function sanitizeHighlight(value) {
  return escapeHtml(value)
    .replaceAll("&lt;mark&gt;", "<mark>")
    .replaceAll("&lt;/mark&gt;", "</mark>");
}

// --------------------------------------------------------------------- ask
async function doAsk(question, mode) {
  const limit = askLimit();
  els.askResults.classList.remove("degraded");
  els.askResults.innerHTML = `<p class="empty">Reading memory...</p>`;
  els.askMeta.textContent = mode === "retrieve" ? "retrieving..." : "asking...";

  if (mode === "retrieve") {
    const result = await api(`/api/ask/retrieve?q=${encodeURIComponent(question)}&limit=${encodeURIComponent(limit)}`);
    renderAskRetrieve(result);
    return;
  }

  const result = await api("/api/ask", {
    method: "POST",
    body: JSON.stringify({ question, limit }),
  });
  renderAskResponse(result);
}

function renderAskRetrieve(result) {
  const count = result.results?.length || 0;
  els.askMeta.textContent = `${count} retrieved · ${result.retrievalMode || "unavailable"}`;
  els.askResults.classList.toggle("degraded", Boolean(result.degraded));
  const summary = count
    ? `<div class="ask-answer-label">Retrieve</div><p>${escapeHtml(count)} grounded memory hits.</p>`
    : `<div class="ask-answer-label">Retrieve</div><p>No grounded memory hits.</p>`;
  els.askResults.innerHTML = summary + renderAskCitations(result.results || []);
}

function renderAskResponse(result) {
  const citations = result.citations || [];
  els.askMeta.textContent = `${citations.length} citations · ${result.retrievalMode || "unavailable"}`;
  els.askResults.classList.toggle("degraded", Boolean(result.degraded));
  els.askResults.innerHTML = `<div class="ask-answer">
    <div class="ask-answer-label">Answer</div>
    <div class="answer-copy">${renderAnswerText(result.answer || "")}</div>
    ${renderAnswerSourceLinks(citations)}
  </div>` + renderAskCitations(citations);
}

function renderAnswerText(answer) {
  return escapeHtml(answer)
    .replace(/\[(\d+)\]/g, (_match, n) => `<a href="#ask-citation-${n}" class="answer-citation">[${n}]</a>`)
    .replace(/\n/g, "<br>");
}

function askLimit() {
  const value = Number(new FormData(els.askForm).get("limit"));
  return Number.isFinite(value) ? Math.max(1, Math.min(value, 50)) : 6;
}

function renderAnswerSourceLinks(citations) {
  if (!citations.length) return "";
  return `<div class="answer-source-links" aria-label="Answer sources">` +
    citations.map(citation => `<a href="#ask-citation-${escapeHtml(citation.number)}">${escapeHtml(citation.number)} ${escapeHtml(citation.title || "memory")}</a>`).join("") +
    `</div>`;
}

function renderAskCitations(citations) {
  if (!citations.length) {
    return `<p class="empty">No citations.</p>`;
  }
  return `<div class="ask-citations">${citations.map(renderAskCitation).join("")}</div>`;
}

function renderAskCitation(citation) {
  const source = citation.sourcePath || citation.sessionId || citation.source || "";
  const memoryAction = citation.sessionId
    ? `<a href="#session-${escapeHtml(citation.sessionId)}" class="citation-memory-link ask-open-memory" data-session-id="${escapeHtml(citation.sessionId)}" data-event-id="${escapeHtml(citation.id)}">Open memory</a>`
    : "";
  const sourceHref = sourcePathHref(citation.sourcePath);
  const sourceAction = sourceHref
    ? `<a href="${escapeHtml(sourceHref)}" class="citation-source-link" target="_blank" rel="noreferrer">Source path</a>`
    : "";
  const copySource = source
    ? `<button type="button" class="ghost small-ghost ask-copy-source" data-copy-value="${escapeHtml(source)}" title="Copy source" aria-label="Copy source">Copy</button>`
    : "";
  return `<article class="ask-citation-card" id="ask-citation-${escapeHtml(citation.number)}">
    <div class="ask-citation-head">
      <span class="ask-citation-num">[${escapeHtml(citation.number)}]</span>
      <strong>${escapeHtml(citation.title || "(untitled memory)")}</strong>
      <span class="readout">${escapeHtml(relativeTime(citation.timestamp))}</span>
    </div>
    <div class="ask-citation-source">${escapeHtml(source)}</div>
    <div class="ask-citation-snippet">${escapeHtml(citation.snippet || "")}</div>
    <div class="ask-citation-actions">${memoryAction}${sourceAction}${copySource}</div>
  </article>`;
}

function sourcePathHref(path) {
  if (!path || typeof path !== "string") return "";
  if (path.startsWith("/")) return `file://${path}`;
  if (/^https?:\/\//i.test(path)) return path;
  return "";
}

// ----------------------------------------------------------------- recall
async function doRecall(scope, withinHours) {
  els.recallMeta.textContent = "reading…";
  const params = new URLSearchParams();
  if (scope) params.set("scope", scope);
  params.set("withinHours", String(withinHours));
  params.set("kinds", "decision,handoff");
  const result = await api(`/api/recall?${params.toString()}`);

  els.recallStage.hidden = false;
  window.BlackBoxConstellation?.destroy();
  els.constellation.innerHTML = "";
  els.memoryDetail.innerHTML = "";
  const scopeLabel = result.scope || "all repos";
  const metaScopeLabel = result.scope ? `“${result.scope}”` : "all repos";
  els.recallMeta.textContent = `${result.count} recalled · ${metaScopeLabel}`;

  if (!result.items.length) {
    els.memoryDetail.innerHTML = `<p class="recall-empty">No prior intent committed for ${escapeHtml(metaScopeLabel)} yet — capture a decision, then recall it back.</p>`;
    return;
  }
  window.BlackBoxConstellation.render(els.constellation, result.items, {
    scopeLabel,
    spineEventIds: state.spineEventIds,
    locateOnSpine,
    onSelect: renderRecallDetail,
  });
  renderRecallDetail(result.items[0]);
}

function renderMemory(item, i) {
  const src = (item.source || "unknown").toLowerCase();
  const headline = escapeHtml(item.headline || "(untitled)");
  const rationale = item.rationale ? `<div class="mem-rationale">${escapeHtml(item.rationale)}</div>` : "";

  let alts = "";
  if (Array.isArray(item.alternatives) && item.alternatives.length) {
    alts = `<div class="mem-alts-label">considered</div><div class="mem-alts">${escapeHtml(item.alternatives.join(" · "))}</div>`;
  }
  let loops = "";
  if (Array.isArray(item.openLoops) && item.openLoops.length) {
    loops = `<div class="mem-loops-label">open loops</div><div class="mem-loops">` +
      item.openLoops.map(l => `<div class="loop">${escapeHtml(l)}</div>`).join("") + `</div>`;
  }
  const next = item.nextAction ? `<div class="mem-alts-label">next action</div><div class="mem-alts">${escapeHtml(item.nextAction)}</div>` : "";

  let foot;
  if (item.confidence != null) {
    const pct = Math.round(Math.max(0, Math.min(1, Number(item.confidence))) * 100);
    foot = `<div class="mem-foot"><span class="readout">${escapeHtml(item.repo || "")}</span>
      <div class="mem-conf"><span class="lbl">conf</span>
        <div class="bar"><span style="width:${pct}%"></span></div>
        <span class="num">${escapeHtml(formatConf(item.confidence))}</span></div></div>`;
  } else {
    const to = item.toAgent ? ` → ${escapeHtml(item.toAgent)}` : "";
    foot = `<div class="mem-foot"><span class="readout">${escapeHtml(item.repo || "")}${to}</span></div>`;
  }

  return `<article class="mem" data-event-id="${escapeHtml(item.eventId)}" style="--i:${i}">
    <div class="mem-head">
      <span class="mem-kind">${escapeHtml(item.kind || "intent")}</span>
      <span class="src src--${escapeHtml(src)}">${escapeHtml(src)}</span>
      <span class="mem-time">${escapeHtml(relativeTime(item.observedAt))}</span>
    </div>
    <div class="mem-headline">${headline}</div>
    ${rationale}${alts}${loops}${next}${foot}
  </article>`;
}

function renderRecallDetail(item) {
  els.memoryDetail.innerHTML = renderMemory(item, 0);
  wireMemoryCards(els.memoryDetail);
}

// Click a recalled memory to find it on the active Spine — the coordination edge, made tangible.
function wireMemoryCards(root) {
  root.querySelectorAll(".mem").forEach(card => {
    const id = card.dataset.eventId;
    if (state.spineEventIds.has(id)) {
      card.style.cursor = "pointer";
      card.title = "Locate on the Cognition Spine";
      card.addEventListener("click", () => locateOnSpine(id));
    }
  });
}

function locateOnSpine(eventId) {
  const node = els.spine.querySelector(`.node[data-event-id="${CSS.escape(eventId)}"]`);
  if (!node) return;
  node.scrollIntoView({ behavior: "smooth", block: "center" });
  node.animate(
    [
      { boxShadow: "inset 0 0 0 0 transparent", offset: 0 },
      { boxShadow: `inset 0 0 0 2px var(--amber)`, offset: 0.3 },
      { boxShadow: "inset 0 0 0 0 transparent", offset: 1 },
    ],
    { duration: 1100, easing: "ease-out" }
  );
}

// ----------------------------------------------------------------- actions
els.stageTabs.addEventListener("click", event => {
  loadAskStatus();
  const button = event.target.closest("[data-tab]");
  if (button) activateTab(button.dataset.tab);
});

els.askForm.addEventListener("submit", async event => {
  event.preventDefault();
  const question = (new FormData(event.currentTarget).get("question") || "").trim();
  if (!question) return;
  const buttons = [...event.currentTarget.querySelectorAll("button")];
  buttons.forEach(button => { button.disabled = true; });
  try {
    await doAsk(question, "ask");
  } catch (err) {
    els.askResults.classList.add("degraded");
    els.askResults.innerHTML = `<div class="ask-answer-label">Error</div><p>${escapeHtml(err.message)}</p>`;
    els.askMeta.textContent = "ask failed";
  } finally {
    buttons.forEach(button => { button.disabled = false; });
  }
});

els.retrieveButton.addEventListener("click", async () => {
  const question = (new FormData(els.askForm).get("question") || "").trim();
  if (!question) return;
  const buttons = [...els.askForm.querySelectorAll("button")];
  buttons.forEach(button => { button.disabled = true; });
  try {
    await doAsk(question, "retrieve");
  } catch (err) {
    els.askResults.classList.add("degraded");
    els.askResults.innerHTML = `<div class="ask-answer-label">Error</div><p>${escapeHtml(err.message)}</p>`;
    els.askMeta.textContent = "retrieve failed";
  } finally {
    buttons.forEach(button => { button.disabled = false; });
  }
});

els.askResults.addEventListener("click", async event => {
  const open = event.target.closest(".ask-open-memory[data-session-id], .ask-open-session[data-session-id]");
  if (open) {
    event.preventDefault();
    await selectSession(open.dataset.sessionId);
    activateTab("spine");
    if (open.dataset.eventId) {
      requestAnimationFrame(() => locateOnSpine(open.dataset.eventId));
    }
    return;
  }
  const copy = event.target.closest(".ask-copy-source[data-copy-value]");
  if (copy) {
    await copyValue(copy.dataset.copyValue, copy);
  }
});

els.sessions.addEventListener("click", event => {
  const toggle = event.target.closest(".session-group-toggle[data-project-key]");
  if (toggle) {
    const key = toggle.dataset.projectKey;
    if (state.expandedSessionGroups.has(key)) {
      state.expandedSessionGroups.delete(key);
    } else {
      state.expandedSessionGroups.add(key);
    }
    saveExpandedSessionGroups();
    renderSessionsRail();
    return;
  }
  const copy = event.target.closest(".copy-button[data-session-id]");
  if (copy) {
    event.preventDefault();
    event.stopPropagation();
    const session = state.sessions.find(item => item.id === copy.dataset.sessionId);
    if (session) copySessionIds(session, copy);
    return;
  }
  const row = event.target.closest(".channel");
  if (row) {
    selectSession(row.dataset.id);
    activateTab("spine");
  }
});

els.expandAllSessionsButton.addEventListener("click", expandAllSessionGroups);
els.collapseAllSessionsButton.addEventListener("click", collapseAllSessionGroups);

els.sessionIdentity.addEventListener("click", event => {
  const button = event.target.closest(".id-chip[data-copy-value]");
  if (button) copyValue(button.dataset.copyValue, button);
});

els.refreshButton.addEventListener("click", async () => {
  try { await Promise.all([loadStatus(), loadAskStatus(), loadSessions(false)]); }
  catch (err) { console.error(err); }
});

els.recallForm.addEventListener("submit", async event => {
  event.preventDefault();
  const data = new FormData(event.currentTarget);
  try {
    await doRecall((data.get("scope") || "").trim(), Number(data.get("withinHours")));
  } catch (err) {
    els.recallMeta.textContent = `recall failed: ${err.message}`;
  }
});

els.clearRecallButton.addEventListener("click", clearRecall);

els.toggleRecallButton.addEventListener("click", () => {
  const collapsed = !els.recallPanel.classList.contains("collapsed");
  els.recallPanel.classList.toggle("collapsed", collapsed);
  els.recallBody.hidden = collapsed;
  els.toggleRecallButton.textContent = collapsed ? "▾" : "▴";
  els.toggleRecallButton.title = collapsed ? "Expand recall" : "Collapse recall";
  els.toggleRecallButton.setAttribute("aria-label", collapsed ? "Expand recall" : "Collapse recall");
  els.toggleRecallButton.setAttribute("aria-expanded", String(!collapsed));
});

function clearRecall() {
  els.recallForm.reset();
  els.recallMeta.textContent = "";
  els.recallStage.hidden = true;
  window.BlackBoxConstellation?.destroy();
  els.constellation.innerHTML = "";
  els.memoryDetail.innerHTML = "";
}

els.captureForm.addEventListener("submit", async event => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget).entries());
  if (!data.text || !data.text.trim()) return;
  const button = event.currentTarget.querySelector("button");
  button.disabled = true;
  try {
    await api("/api/events", {
      method: "POST",
      body: JSON.stringify({
        source: data.source,
        clientSessionId: data.clientSessionId,
        eventType: "Observation",
        role: "user",
        text: data.text,
        metadata: { kind: "observation" },
        observedAt: new Date().toISOString(),
      }),
    });
    event.currentTarget.elements.text.value = "";
    await Promise.all([loadStatus(true), loadSessions(true)]);
  } catch (err) {
    console.error(err);
  } finally {
    button.disabled = false;
  }
});

els.searchForm.addEventListener("submit", async event => {
  event.preventDefault();
  const query = (new FormData(event.currentTarget).get("query") || "").trim();
  if (!query) return;
  try {
    const results = await api(`/api/search?q=${encodeURIComponent(query)}&limit=20`);
    renderSearch(results);
  } catch (err) {
    els.searchResults.innerHTML = `<p class="empty">${escapeHtml(err.message)}</p>`;
  }
});

els.copySessionButton.addEventListener("click", () => {
  const session = state.sessions.find(item => item.id === state.activeSessionId);
  if (session) copySessionIds(session, els.copySessionButton);
});

els.toolFilter.addEventListener("click", event => {
  const button = event.target.closest(".filter-chip[data-filter]");
  if (!button) return;
  state.activeToolFilter = button.dataset.filter;
  renderFilteredSpine();
});

async function copySessionIds(session, button) {
  const payload = [
    `blackBoxSessionId: ${session.id}`,
    `clientSessionId: ${session.clientSessionId}`,
  ].join("\n");
  await copyValue(payload, button);
}

async function copyValue(value, button) {
  try {
    await copyText(value);
    flashCopy(button, true);
  } catch (err) {
    console.error(err);
    flashCopy(button, false);
  }
}

async function copyText(value) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  textarea.remove();
}

function flashCopy(button, ok = true) {
  button.classList.remove("copied", "copy-failed");
  button.classList.add(ok ? "copied" : "copy-failed");
  window.setTimeout(() => {
    button.classList.remove("copied", "copy-failed");
  }, 900);
}

function renderSearch(results) {
  const local = results.local || [];
  const elastic = results.elastic || [];
  const merged = mergeSearchResults(local, elastic);
  const health = results.elasticHealth || {};
  const backend = health.available === true
    ? "elastic fuzzy"
    : health.enabled === false ? "local" : "local · elastic offline";
  els.searchMeta.textContent = `${merged.length} hits · ${backend}`;
  if (!merged.length) {
    els.searchResults.innerHTML = `<p class="empty">No matches.</p>`;
    return;
  }
  els.searchResults.innerHTML = merged.map(renderSearchHit).join("");
}

function mergeSearchResults(local, elastic) {
  const localById = new Map(local.map(event => [event.id, event]));
  const seen = new Set();
  const merged = [];
  for (const hit of elastic) {
    const event = localById.get(hit.id) || eventFromElastic(hit);
    seen.add(hit.id);
    merged.push({
      event,
      score: Number(hit.score),
      origin: localById.has(hit.id) ? "elastic + local" : "elastic",
      highlight: hit.highlight || {},
    });
  }
  for (const event of local) {
    if (!seen.has(event.id)) {
      merged.push({ event, score: null, origin: "local", highlight: {} });
    }
  }
  return merged;
}

function eventFromElastic(hit) {
  const source = hit.source || {};
  return {
    id: hit.id,
    sessionId: source.sessionId || "",
    source: source.source || "unknown",
    clientSessionId: source.clientSessionId || "",
    turnId: source.turnId || "",
    eventType: source.eventType || "Event",
    role: "",
    text: source.text || "",
    toolName: source.toolName || "",
    toolInputJson: null,
    toolOutputJson: null,
    metadata: {},
    observedAt: source.observedAt || "",
    title: source.title || "",
    cwd: source.cwd || "",
  };
}

function renderSearchHit(hit, i) {
  const event = hit.event;
  const src = (event.source || "unknown").toLowerCase();
  const title = highlightText(hit.highlight, "title", event.title || event.metadata?.title || event.eventType || "Event", 120);
  const body = highlightText(hit.highlight, "text", event.text || event.toolName || event.clientSessionId || "", 420);
  const score = hit.score == null || !Number.isFinite(hit.score)
    ? ""
    : `<span class="result-score">${hit.score.toFixed(2)}</span>`;
  const where = event.cwd || event.clientSessionId || event.sessionId || "";
  return `<article class="result" data-session-id="${escapeHtml(event.sessionId)}" data-event-id="${escapeHtml(event.id)}" tabindex="0" style="--i:${i}">
    <div class="result-head">
      <span class="result-origin">${escapeHtml(hit.origin)}</span>
      ${score}
      <span class="result-time">${escapeHtml(relativeTime(event.observedAt))}</span>
    </div>
    <div class="result-title">
      <span class="src src--${escapeHtml(src)}">${escapeHtml(src)}</span>
      <span>${title}</span>
    </div>
    <div class="result-body">${body}</div>
    <div class="result-foot">${escapeHtml(event.eventType || "Event")}${where ? ` · ${escapeHtml(where)}` : ""}</div>
  </article>`;
}

els.searchResults.addEventListener("click", event => {
  const row = event.target.closest(".result[data-session-id]");
  if (row) openSearchResult(row);
});

els.searchResults.addEventListener("keydown", event => {
  if (event.key !== "Enter") return;
  const row = event.target.closest(".result[data-session-id]");
  if (row) openSearchResult(row);
});

async function openSearchResult(row) {
  if (!row.dataset.sessionId) return;
  await selectSession(row.dataset.sessionId);
  activateTab("spine");
  if (state.activeToolFilter !== "all") {
    state.activeToolFilter = "all";
    renderFilteredSpine();
  }
  requestAnimationFrame(() => locateOnSpine(row.dataset.eventId));
}

els.summarizeButton.addEventListener("click", async () => {
  if (!state.activeSessionId) return;
  const button = els.summarizeButton;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "Summarizing…";
  try {
    const session = await api(`/api/sessions/${encodeURIComponent(state.activeSessionId)}/summarize`, { method: "POST" });
    els.spineTitle.textContent = session.title;
    els.summary.hidden = !session.summary;
    els.summary.textContent = session.summary || "";
    setSummaryExportState(session);
    await loadSessions(false);
  } catch (err) {
    els.summary.hidden = false;
    els.summary.textContent = `Summarize failed: ${err.message}`;
  } finally {
    button.textContent = original;
    button.disabled = false;
  }
});

els.exportSummaryButton.addEventListener("click", async () => {
  if (!state.activeSessionId) return;
  const targetId = els.exportTargetSelect.value || state.exportTargets[0]?.id;
  if (!targetId) return;
  const target = state.exportTargets.find(target => target.id === targetId);
  const targetLabel = target?.label || targetId;
  const button = els.exportSummaryButton;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "Exporting…";
  els.summaryExportStatus.hidden = false;
  els.summaryExportStatus.textContent = `Exporting summary to ${targetLabel}…`;
  els.summaryExportStatus.classList.remove("error");
  try {
    const result = await api(`/api/sessions/${encodeURIComponent(state.activeSessionId)}/exports/${encodeURIComponent(targetId)}`, { method: "POST" });
    els.summaryExportStatus.textContent = `Exported to ${result.targetLabel || targetLabel}: ${result.relativePath || result.path}`;
  } catch (err) {
    els.summaryExportStatus.textContent = `Export failed: ${err.message}`;
    els.summaryExportStatus.classList.add("error");
  } finally {
    button.textContent = original;
    const session = state.sessions.find(s => s.id === state.activeSessionId);
    setSummaryExportState(session);
  }
});

window.addEventListener("resize", () => {
  if (!els.recallStage.hidden) window.BlackBoxConstellation?.redraw();
});

// ----------------------------------------------------------------- query bar (P3)
// Enhances the existing search input with KQL-lite token highlighting + autocomplete.
// Purely additive: the input keeps name="query"; the submit path below (els.searchForm
// submit handler) is the ONLY submit path and is left untouched. The query bar attaches
// input/keyup/focus listeners on its own dispatch channel and never preventDefaults the
// submit, so plain free-text search behaves identically (n=1-9,19-24).

// Resolve value suggestions over /api/search/values for a (field, prefix). Honours an
// AbortSignal so a superseded request is cancelled; degrades to [] on any failure so a
// suggestion miss never surfaces in the submit handler's error path.
function fetchSearchValues(field, prefix, signal) {
  if (typeof window.fetch !== "function") return Promise.resolve([]);
  const url =
    `/api/search/values?field=${encodeURIComponent(field)}` +
    `&prefix=${encodeURIComponent(prefix || "")}&limit=20`;
  return window
    .fetch(url, { headers: { Accept: "application/json" }, signal })
    .then(response => (response.ok ? response.json() : []))
    .then(values => (Array.isArray(values) ? values : []))
    .catch(() => []);
}

// Load the field catalogue once at init and hand it to the query bar so the bar does not
// have to lazy-fetch it itself. Failures degrade to [] — the bar falls back to its own
// lazy /api/search/fields fetch, so autocomplete still works.
function attachQueryBar(fields) {
  if (!window.BlackBoxQueryBar || !document.querySelector("#queryInput")) return;
  // attach(form, options): the named input + .qb-overlay sibling resolve from els.searchForm;
  // `pop` wires the cursor-context suggestion layer, which consumes fetchValues. Returns null
  // if the input/overlay are missing — we discard the return (no destroy needed at page scope).
  window.BlackBoxQueryBar.attach(els.searchForm, {
    fields,
    fetchValues: fetchSearchValues,
    pop: document.querySelector("#qbPop"),
  });
}

if (window.BlackBoxQueryBar && document.querySelector("#queryInput")) {
  fetch("/api/search/fields", { headers: { Accept: "application/json" } })
    .then(response => (response.ok ? response.json() : []))
    .catch(() => [])
    .then(fields => attachQueryBar(Array.isArray(fields) ? fields : []));
}

// ----------------------------------------------------------------- boot
Promise.all([loadStatus(), loadAskStatus(), loadExportTargets()])
  .then(() => loadSessions(true))
  .catch(error => {
  els.readouts.innerHTML = gauge("recorder", "degraded", "unreachable");
  els.spine.innerHTML = `<p class="empty">Could not reach the recorder: ${escapeHtml(error.message)}</p>`;
});
