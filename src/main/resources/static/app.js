// Black Box — instrument logic. Reads the local recorder over /api/*, draws the Cognition Spine,
// and fires the recall beam. No framework, no build step. The screen is an instrument, not a page.

const state = {
  sessions: [],
  exportTargets: [],
  activeTab: "spine",
  activeSessionId: null,
  activeEvents: [],
  projects: [],
  activeProjectKey: null,
  projectSessions: [],
  projectTimeline: [],
  selectedProjectSessionIds: new Set(),
  projectMeld: null,
  activeToolFilter: "all",
  spineEventIds: new Set(),
  expandedEventIds: new Set(),
  expandedSessionGroups: new Set(),
  sessionGroupStateLoaded: false,
  sessionQuery: "",
  railCollapsed: false,
  outlineCollapsed: false,
  askAvailable: false,
};

const SESSION_GROUP_STORAGE_KEY = "blackbox.sessions.expandedGroups.v1";
const RAIL_COLLAPSED_STORAGE_KEY = "blackbox.rail.collapsed.v1";
const OUTLINE_COLLAPSED_STORAGE_KEY = "blackbox.sessionOutline.collapsed.v1";
const NO_PROJECT_GROUP_KEY = "__no_project__";
const NO_PROJECT_GROUP_LABEL = "No project / manual / system";

const els = {
  readouts: document.querySelector("#readouts"),
  deck: document.querySelector(".deck"),
  projectRail: document.querySelector("#projectRail"),
  stageTabs: document.querySelector(".stage-tabs"),
  tabPanels: document.querySelectorAll("[data-tab-panel]"),
  sessions: document.querySelector("#sessions"),
  sessionCount: document.querySelector("#sessionCount"),
  sessionSearchInput: document.querySelector("#sessionSearchInput"),
  toggleRailButton: document.querySelector("#toggleRailButton"),
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
  projectList: document.querySelector("#projectList"),
  projectCount: document.querySelector("#projectCount"),
  projectSessions: document.querySelector("#projectSessions"),
  projectTimeline: document.querySelector("#projectTimeline"),
  projectsMeta: document.querySelector("#projectsMeta"),
  projectTimelineMeta: document.querySelector("#projectTimelineMeta"),
  projectMeldTray: document.querySelector("#projectMeldTray"),
  projectMeldForm: document.querySelector("#projectMeldForm"),
  projectMeldMode: document.querySelector("#projectMeldMode"),
  projectMeldProvider: document.querySelector("#projectMeldProvider"),
  projectMeldModel: document.querySelector("#projectMeldModel"),
  projectMeldSelectedCount: document.querySelector("#projectMeldSelectedCount"),
  projectMeldStatus: document.querySelector("#projectMeldStatus"),
  projectMeldPreviewButton: document.querySelector("#projectMeldPreviewButton"),
  projectMeldOutput: document.querySelector("#projectMeldOutput"),
  projectMeldOutputTitle: document.querySelector("#projectMeldOutputTitle"),
  projectMeldText: document.querySelector("#projectMeldText"),
  copyProjectMeldButton: document.querySelector("#copyProjectMeldButton"),
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
  summaryPreview: document.querySelector("#summaryPreview"),
  summaryOpenButton: document.querySelector("#summaryOpenButton"),
  summaryDialog: document.querySelector("#summaryDialog"),
  summaryDialogText: document.querySelector("#summaryDialogText"),
  summaryCloseButton: document.querySelector("#summaryCloseButton"),
  summarizeButton: document.querySelector("#summarizeButton"),
  exportTargetSelect: document.querySelector("#exportTargetSelect"),
  exportSummaryButton: document.querySelector("#exportSummaryButton"),
  summaryExportStatus: document.querySelector("#summaryExportStatus"),
  sessionOutline: document.querySelector("#sessionOutline"),
  outlineBody: document.querySelector("#outlineBody"),
  toggleOutlineButton: document.querySelector("#toggleOutlineButton"),
  outlinePopout: document.querySelector("#outlinePopout"),
  outlinePopoutTitle: document.querySelector("#outlinePopoutTitle"),
  outlinePopoutBody: document.querySelector("#outlinePopoutBody"),
  outlinePopoutCloseButton: document.querySelector("#outlinePopoutCloseButton"),
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
  state.sessions = await api("/api/sessions?limit=250");
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
    renderSummaryCard(null);
    renderSessionOutline([]);
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
  if (!groups.length) {
    els.sessions.innerHTML = `<p class="empty">No matching sessions. Try a project name, file path, source, or session title.</p>`;
    return;
  }
  els.sessions.innerHTML = groups.map(renderSessionGroup).join("");
}

function renderSessionGroup(group) {
  const expanded = Boolean(state.sessionQuery) || state.expandedSessionGroups.has(group.key);
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
  const query = normalizeSearch(state.sessionQuery);
  const projectByKey = new Map(state.projects.map(project => [project.projectKey, project]));
  const projectOrder = new Map(state.projects.map((project, index) => [project.projectKey, index]));
  const groupsByKey = new Map();
  for (const session of sessions) {
    const key = sessionProjectGroupKey(session);
    const canonical = canonicalProjectKey(session?.cwd);
    const project = projectByKey.get(key);
    const label = project?.label || (canonical === NO_PROJECT_GROUP_KEY ? NO_PROJECT_GROUP_LABEL : formatProjectPath(canonical));
    const score = sessionProjectScore(query, session, project, label, canonical);
    if (query && score <= 0) continue;
    if (!groupsByKey.has(key)) {
      groupsByKey.set(key, {
        key,
        label,
        canonical,
        project,
        noProject: canonical === NO_PROJECT_GROUP_KEY,
        sessions: [],
        score: 0,
        order: projectOrder.has(key) ? projectOrder.get(key) : Number.MAX_SAFE_INTEGER,
      });
    }
    const group = groupsByKey.get(key);
    group.sessions.push(session);
    group.score = Math.max(group.score, score);
  }
  const groups = [...groupsByKey.values()];
  return groups.sort((a, b) => {
    if (a.noProject !== b.noProject) return a.noProject ? 1 : -1;
    if (query && b.score !== a.score) return b.score - a.score;
    if (a.order !== b.order) return a.order - b.order;
    return b.sessions[0]?.lastSeenAt?.localeCompare(a.sessions[0]?.lastSeenAt || "") || 0;
  });
}

function sessionProjectGroupKey(session) {
  return projectKeyFromCanonical(canonicalProjectKey(session?.cwd));
}

const HOME_DIR_PATTERN = /^(\/Users\/[^/]+|\/home\/[^/]+)(?=\/|$)/;

function canonicalProjectKey(path) {
  let value = String(path || "").trim();
  if (!value) return NO_PROJECT_GROUP_KEY;
  while (value.length > 1 && value.endsWith("/")) value = value.slice(0, -1);
  return value || "/";
}

function projectKeyFromCanonical(canonical) {
  const bytes = new TextEncoder().encode(canonicalProjectKey(canonical));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return window.btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function formatProjectPath(path) {
  const value = String(path || "").trim();
  if (!value || value === NO_PROJECT_GROUP_KEY) return "";
  const match = value.match(HOME_DIR_PATTERN);
  if (!match) return value;
  const rest = value.slice(match[1].length);
  return rest ? `~${rest}` : "~";
}

function sessionProjectScore(query, session, project, label, canonical) {
  if (!query) return 1;
  return Math.max(
    fuzzyScore(query, label),
    fuzzyScore(query, canonical),
    fuzzyScore(query, project?.canonicalKey || ""),
    fuzzyScore(query, session.title || ""),
    fuzzyScore(query, session.clientSessionId || ""),
    fuzzyScore(query, session.source || ""),
    fuzzyScore(query, session.cwd || "")
  );
}

function normalizeSearch(value) {
  return String(value || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function fuzzyScore(query, value) {
  const q = normalizeSearch(query);
  const v = normalizeSearch(value);
  if (!q || !v) return 0;
  if (v.includes(q)) return 100 + q.length;
  const tokens = q.split(/[^a-z0-9/._~]+/).filter(Boolean);
  if (!tokens.length) return 0;
  let score = 0;
  for (const token of tokens) {
    const tokenScore = fuzzyTokenScore(token, v);
    if (tokenScore <= 0) return 0;
    score += tokenScore;
  }
  return score;
}

function fuzzyTokenScore(token, value) {
  if (value.includes(token)) return 30 + token.length;
  if (token.length < 3) return 0;
  let cursor = 0;
  let subsequence = 0;
  for (const char of token) {
    const found = value.indexOf(char, cursor);
    if (found < 0) return 0;
    subsequence += found === cursor ? 3 : 1;
    cursor = found + 1;
  }
  return subsequence;
}

// Display-only: collapse any home-directory prefix (start or mid-string) to ~.
// Copy payloads intentionally keep full paths.
const HOME_PATH_GLOBAL_PATTERN = /(?:\/Users|\/home)\/[A-Za-z0-9._-]+/g;

function tildify(value) {
  return String(value ?? "").replace(HOME_PATH_GLOBAL_PATTERN, "~");
}

function looksLikeJson(value) {
  const trimmed = String(value ?? "").trim();
  return trimmed.startsWith("{") || trimmed.startsWith("[");
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
    const where = session.cwd ? ` · ${formatProjectPath(session.cwd)}` : "";
    els.spineMeta.textContent = `${session.source}${where} · ${session.eventCount} events`;
    els.sessionIdentity.innerHTML = renderSessionIdentity(session);
    els.copySessionButton.disabled = false;
    renderSummaryCard(session);
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
  renderSessionOutline(events);
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

function renderSummaryCard(session) {
  const summary = session?.summary || "";
  els.summary.hidden = !summary;
  if (!summary) {
    els.summaryPreview.textContent = "";
    els.summaryDialogText.textContent = "";
    return;
  }
  els.summaryPreview.textContent = summary;
  els.summaryDialogText.textContent = summary;
}

function openSummaryDialog() {
  if (els.summary.hidden || !els.summaryDialogText.textContent.trim()) return;
  els.summaryDialog.hidden = false;
  els.summaryCloseButton.focus();
}

function closeSummaryDialog() {
  els.summaryDialog.hidden = true;
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
    const headline = escapeHtml(tildify(meta.decision || firstLine(event.text) || "Decision"));
    const rationale = meta.rationale ? `<div class="mem-rationale" style="color:var(--muted);font-size:13px">${escapeHtml(tildify(meta.rationale))}</div>` : "";
    const loops = loopList(meta.openLoops);
    const conf = (meta.confidence != null) ? `<span>confidence <b>${escapeHtml(formatConf(meta.confidence))}</b></span>` : "";
    const alts = altsDetails(meta.alternatives);
    body = head("Decision", srcTick(event.source)) +
      `<div class="node-text">${headline}</div>${rationale}${loops}` +
      (conf ? `<div class="node-meta-line">${conf}</div>` : "") + alts;
  } else if (cls === "handoff") {
    const headline = escapeHtml(tildify(meta.contextSummary || firstLine(event.text) || "Handoff"));
    const loops = loopList(meta.openLoops);
    const to = meta.toAgent ? `<span>→ <b>${escapeHtml(meta.toAgent)}</b></span>` : "";
    const next = meta.nextAction ? `<div class="node-meta-line">next: ${escapeHtml(tildify(meta.nextAction))}</div>` : "";
    body = head("Handoff", srcTick(event.source)) +
      `<div class="node-text">${headline}</div>${loops}` +
      (to ? `<div class="node-meta-line">${to}</div>` : "") + next;
  } else if (cls === "tool") {
    const card = toolCallCard(event);
    const toolName = !card && event.toolName ? `<span class="node-tool">${escapeHtml(event.toolName)}</span>` : "";
    // Raw JSON text on tool events duplicates the card's input/output; only prose survives.
    const text = event.text && !looksLikeJson(event.text)
      ? `<div class="node-text">${escapeHtml(clamp(tildify(event.text), 400))}</div>`
      : "";
    body = head(toolDisplayName(event), toolName + srcTick(event.source)) + text + card;
  } else if (cls === "error") {
    const card = toolCallCard(event);
    const fallback = card ? "Failure" : (event.text || event.toolOutputJson || "Failure");
    const headline = event.text && !looksLikeJson(event.text) ? event.text : fallback;
    body = head(event.eventType || "Error", srcTick(event.source)) +
      `<div class="node-text">${escapeHtml(clamp(tildify(headline), 600))}</div>` + card;
  } else {
    body = head(event.eventType || "Event", srcTick(event.source)) +
      `<div class="node-text">${escapeHtml(clamp(tildify(event.text || event.toolInputJson || ""), 800))}</div>`;
  }

  const expanded = state.expandedEventIds.has(event.id);
  const summary = eventSummary(event, cls);
  return `<article class="node node--${cls} ${expanded ? "is-expanded" : "is-collapsed"}" data-event-id="${escapeHtml(event.id)}" style="--i:${i}">
    <div class="node-rail"><span class="node-dot" style="--dot:${dotSize(event)}px"></span></div>
    <div class="node-card">
      <button type="button" class="node-summary" data-toggle-event="${escapeHtml(event.id)}" aria-expanded="${String(expanded)}">
        <span class="node-summary-type">${escapeHtml(summary.type)}</span>
        <span class="node-summary-text">${escapeHtml(tildify(summary.text))}</span>
        <span class="node-summary-time">${escapeHtml(time)}</span>
      </button>
      <div class="node-body" ${expanded ? "" : "hidden"}>${body}</div>
    </div>
  </article>`;
}

function eventSummary(event, cls = classify(event)) {
  if (cls === "tool") return toolActionSummary(event);
  const meta = event.metadata || {};
  if (cls === "decision") {
    return { type: "Decision", text: meta.decision || firstLine(event.text) || "Decision" };
  }
  if (cls === "handoff") {
    return { type: "Handoff", text: meta.contextSummary || firstLine(event.text) || "Handoff" };
  }
  if (cls === "error") {
    return { type: "Error", text: firstLine(event.text || event.toolOutputJson || event.eventType || "Failure") };
  }
  return { type: event.eventType || "Event", text: firstLine(event.text || event.toolInputJson || "") || "Event" };
}

function toolDisplayName(event) {
  return toolActionSummary(event).type;
}

function toolActionSummary(event) {
  const tool = event.toolName || event.eventType || "Tool";
  const args = parseToolArgs(event.toolInputJson);
  const pKey = args ? primaryArgKey(args) : null;
  const primary = pKey ? String(args[pKey]).trim() : "";
  const verb = toolVerb(tool, pKey);
  const target = primary || firstLine(event.text || event.toolInputJson || event.toolOutputJson || "");
  return {
    type: verb,
    text: target || tool,
    tool,
    target,
  };
}

function toolVerb(tool, primaryKey = "") {
  const value = String(tool || "").toLowerCase();
  if (/multiedit|edit|write|patch/.test(value)) return "Edit";
  if (/read|cat|sed|open/.test(value)) return "Read";
  if (/grep|search|rg|find/.test(value)) return "Search";
  if (/glob|list|ls/.test(value)) return "List";
  if (/bash|shell|exec|command/.test(value)) return "Run";
  if (/web|fetch|curl|http/.test(value)) return "Fetch";
  if (/file_path|filepath|target_file|path/.test(primaryKey)) return "Use";
  return tool || "Tool";
}

function toggleNode(eventId) {
  if (state.expandedEventIds.has(eventId)) {
    state.expandedEventIds.delete(eventId);
  } else {
    state.expandedEventIds.add(eventId);
  }
  const node = els.spine.querySelector(`.node[data-event-id="${CSS.escape(eventId)}"]`);
  if (!node) return;
  const expanded = state.expandedEventIds.has(eventId);
  node.classList.toggle("is-expanded", expanded);
  node.classList.toggle("is-collapsed", !expanded);
  const button = node.querySelector(".node-summary");
  const body = node.querySelector(".node-body");
  if (button) button.setAttribute("aria-expanded", String(expanded));
  if (body) body.hidden = !expanded;
}

function srcTick(source) {
  const s = (source || "unknown").toLowerCase();
  return `<span class="src src--${escapeHtml(s)}" style="font-size:9px">${escapeHtml(s)}</span>`;
}

function loopList(loops) {
  if (!Array.isArray(loops) || !loops.length) return "";
  return `<div class="node-loops">` +
    loops.map(l => `<div class="loop">${escapeHtml(tildify(l))}</div>`).join("") + `</div>`;
}

function altsDetails(alts) {
  if (!Array.isArray(alts) || !alts.length) return "";
  return `<details><summary>considered ${alts.length}</summary><pre>${escapeHtml(alts.join("\n"))}</pre></details>`;
}

// ------------------------------------------------------------ tool call cards
// Order matters: the first key present with a non-empty string value becomes the
// prominent "primary argument" — the shell command for Bash, the path for Read, etc.
const PRIMARY_ARG_KEYS = [
  "command", "cmd", "script", "file_path", "filePath", "target_file", "path",
  "url", "query", "pattern", "prompt", "question", "text", "content", "input",
];

function parseToolArgs(raw) {
  if (!raw || typeof raw !== "string") return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null;
  } catch (_) {
    return null;
  }
}

function primaryArgKey(args) {
  for (const key of PRIMARY_ARG_KEYS) {
    if (typeof args[key] === "string" && args[key].trim()) return key;
  }
  return Object.keys(args).find(key => typeof args[key] === "string" && args[key].trim()) || null;
}

function prettyJson(raw) {
  if (!raw) return "";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch (_) {
    return raw;
  }
}

function toolCallCard(event) {
  if (!event.toolInputJson && !event.toolOutputJson) return "";
  const args = parseToolArgs(event.toolInputJson);
  const pKey = args ? primaryArgKey(args) : null;
  const actions = [];
  const sections = [];

  if (args) {
    if (pKey) {
      const value = String(args[pKey]);
      const long = value.length > 360 || value.split("\n").length > 6;
      actions.push(cardButton("Copy", "primary", event.id, `Copy ${pKey}`));
      if (long) {
        actions.push(`<button type="button" class="tool-card-button" data-expand-target="primary" title="Show the full ${escapeHtml(pKey)}">Expand</button>`);
      }
      sections.push(`<pre class="tool-card-primary${long ? " clamped" : ""}" data-expandable="primary">${escapeHtml(clamp(tildify(value), 4000))}</pre>`);
    }
    actions.push(cardButton("JSON", "input", event.id, "Copy input as JSON"));
    const rest = Object.entries(args).filter(([key]) => key !== pKey);
    if (rest.length) {
      sections.push(`<dl class="tool-card-params">${rest.map(([key, value]) => renderToolParam(key, value)).join("")}</dl>`);
    }
  } else if (event.toolInputJson) {
    actions.push(cardButton("Copy", "primary", event.id, "Copy input"));
    sections.push(`<pre class="tool-card-primary">${escapeHtml(clamp(tildify(event.toolInputJson), 4000))}</pre>`);
  }

  if (event.toolOutputJson) {
    sections.push(`<details class="tool-card-output">
      <summary>output · ${num(event.toolOutputJson.length)} chars</summary>
      <div class="tool-card-output-body">
        ${cardButton("Copy output", "output", event.id, "Copy tool output")}
        <pre>${escapeHtml(clamp(tildify(prettyJson(event.toolOutputJson)), 4000))}</pre>
      </div>
    </details>`);
  }

  const argLabel = pKey ? `<span class="tool-card-key">${escapeHtml(pKey)}</span>` : "";
  return `<div class="tool-card" data-event-id="${escapeHtml(event.id)}">
    <div class="tool-card-head">
      <span class="tool-card-name">${escapeHtml(event.toolName || event.eventType || "tool")}</span>
      ${argLabel}
      <div class="tool-card-actions">${actions.join("")}</div>
    </div>
    ${sections.join("")}
  </div>`;
}

function cardButton(label, kind, eventId, title) {
  return `<button type="button" class="tool-card-button" data-copy-kind="${escapeHtml(kind)}" data-event-id="${escapeHtml(eventId)}" title="${escapeHtml(title)}">${escapeHtml(label)}</button>`;
}

function renderToolParam(key, value) {
  const label = `<dt>${escapeHtml(key)}</dt>`;
  if (value !== null && typeof value === "object") {
    return `<div class="tool-param tool-param--block">${label}<dd><pre>${escapeHtml(clamp(tildify(JSON.stringify(value, null, 2)), 2000))}</pre></dd></div>`;
  }
  const text = tildify(value);
  if (text.length > 220 || text.includes("\n")) {
    return `<div class="tool-param tool-param--block">${label}<dd><pre>${escapeHtml(clamp(text, 2000))}</pre></dd></div>`;
  }
  return `<div class="tool-param">${label}<dd><code>${escapeHtml(text)}</code></dd></div>`;
}

// Copy payloads resolve from state by event id, so the buttons always copy the FULL
// value even when the on-screen rendering is clamped for display.
function toolCopyValue(eventId, kind) {
  const event = state.activeEvents.find(item => item.id === eventId);
  if (!event) return null;
  if (kind === "primary") {
    const args = parseToolArgs(event.toolInputJson);
    const key = args ? primaryArgKey(args) : null;
    return key ? String(args[key]) : (event.toolInputJson || "");
  }
  if (kind === "input") return prettyJson(event.toolInputJson);
  if (kind === "output") return prettyJson(event.toolOutputJson);
  return null;
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
    return sanitizeHighlight(clamp(tildify(fragments.join(" … ")), max));
  }
  return escapeHtml(clamp(tildify(fallback), max));
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
    <div class="ask-citation-source">${escapeHtml(tildify(source))}</div>
    <div class="ask-citation-snippet">${escapeHtml(tildify(citation.snippet || ""))}</div>
    <div class="ask-citation-actions">${memoryAction}${sourceAction}${copySource}</div>
  </article>`;
}

function sourcePathHref(path) {
  if (!path || typeof path !== "string") return "";
  if (path.startsWith("/")) return `file://${path}`;
  if (/^https?:\/\//i.test(path)) return path;
  return "";
}

// ---------------------------------------------------------------- projects
async function loadProjects(selectFirst = false) {
  state.projects = await api("/api/projects");
  els.projectCount.textContent = `${state.projects.length}`;
  if (!state.projects.length) {
    state.activeProjectKey = null;
    state.projectSessions = [];
    state.projectTimeline = [];
    state.selectedProjectSessionIds = new Set();
    state.projectMeld = null;
    els.projectList.innerHTML = `<p class="empty">No project traces yet.</p>`;
    els.projectSessions.innerHTML = "";
    renderProjectMeldTray();
    renderProjectMeldOutput();
    els.projectTimeline.innerHTML = `<p class="empty">Project storyline is idle.</p>`;
    els.projectsMeta.textContent = "Derived from recorded working directories.";
    els.projectTimelineMeta.textContent = "";
    if (state.sessions.length) renderSessionsRail();
    return;
  }

  const shouldSelect = selectFirst || (state.activeTab === "projects" && (!state.activeProjectKey ||
    !state.projects.some(project => project.projectKey === state.activeProjectKey)));
  renderProjectList();
  if (shouldSelect) {
    await selectProject(state.projects[0].projectKey, { renderList: false });
  } else {
    renderProjectList();
    if (!state.activeProjectKey) {
      els.projectsMeta.textContent = `${num(state.projects.length)} projects · select a project to load its storyline`;
      els.projectTimelineMeta.textContent = "";
    }
  }
  if (state.sessions.length) renderSessionsRail();
}

async function selectProject(projectKey, options = {}) {
  const { renderList = true } = options;
  state.activeProjectKey = projectKey;
  state.selectedProjectSessionIds = new Set();
  if (renderList) renderProjectList();
  const project = state.projects.find(item => item.projectKey === projectKey);
  if (project) {
    els.projectsMeta.textContent =
      `${num(project.sessionCount)} sessions · ${num(project.eventCount)} events · ${project.label}`;
  } else {
    els.projectsMeta.textContent = "Loading project.";
  }
  els.projectSessions.innerHTML = `<p class="empty">Loading sessions...</p>`;
  state.projectMeld = null;
  els.projectMeldStatus.textContent = "";
  renderProjectMeldOutput();
  renderProjectMeldTray();
  els.projectTimeline.innerHTML = `<p class="empty">Loading storyline...</p>`;
  const [sessions, timeline] = await Promise.all([
    api(`/api/projects/${encodeURIComponent(projectKey)}/sessions?limit=100`),
    api(`/api/projects/${encodeURIComponent(projectKey)}/timeline?limit=80`),
  ]);
  state.projectSessions = sessions;
  state.projectTimeline = timeline.items || [];
  reconcileProjectSessionSelection(sessions);
  renderProjectList();
  renderProjectSessions(sessions);
  renderProjectMeldTray();
  renderProjectTimeline(timeline);
}

function renderProjectList() {
  els.projectList.innerHTML = state.projects.map(renderProjectRow).join("");
}

function renderProjectRow(project) {
  const active = project.projectKey === state.activeProjectKey ? "active" : "";
  const melds = Number(project.savedMeldCount || 0);
  const meldMeta = melds ? `<span>${num(melds)} melds</span>` : "";
  return `<button type="button" class="project-row ${active}" data-project-key="${escapeHtml(project.projectKey)}">
    <span class="project-row-title">${escapeHtml(project.label || project.canonicalKey || "Project")}</span>
    <span class="project-row-meta">
      <span>${num(project.sessionCount || 0)} ses</span>
      <span>${num(project.eventCount || 0)} ev</span>
      ${meldMeta}
      <span>${escapeHtml(relativeTime(project.lastSeenAt))}</span>
    </span>
  </button>`;
}

function renderProjectSessions(sessions) {
  if (!sessions.length) {
    els.projectSessions.innerHTML = `<p class="empty">No sessions in this project.</p>`;
    return;
  }
  els.projectSessions.innerHTML = sessions.map(session => {
    const src = (session.source || "unknown").toLowerCase();
    const selected = state.selectedProjectSessionIds.has(session.id);
    return `<article class="project-session ${selected ? "selected" : ""}" data-session-id="${escapeHtml(session.id)}">
      <label class="project-session-pick">
        <input type="checkbox" data-project-session-check="${escapeHtml(session.id)}" ${selected ? "checked" : ""}>
        <span class="src src--${escapeHtml(src)}">${escapeHtml(src)}</span>
        <span class="project-session-title">${escapeHtml(session.title || session.clientSessionId)}</span>
      </label>
      <span class="readout">${num(session.eventCount || 0)} ev · ${escapeHtml(relativeTime(session.lastSeenAt))}</span>
      <button type="button" class="project-session-open" data-open-project-session="${escapeHtml(session.id)}" title="Open session" aria-label="Open session">↗</button>
    </article>`;
  }).join("");
}

function reconcileProjectSessionSelection(sessions) {
  const ids = new Set(sessions.map(session => session.id));
  const selected = [...state.selectedProjectSessionIds].filter(id => ids.has(id));
  if (!selected.length) {
    selected.push(...sessions.slice(0, Math.min(3, sessions.length)).map(session => session.id));
  }
  state.selectedProjectSessionIds = new Set(selected);
}

function renderProjectMeldTray() {
  const selected = state.selectedProjectSessionIds.size;
  els.projectMeldSelectedCount.textContent = `${num(selected)} selected`;
  els.projectMeldPreviewButton.disabled = !state.activeProjectKey || selected === 0;
  if (!state.activeProjectKey) {
    els.projectMeldStatus.textContent = "select a project";
  } else if (selected === 0) {
    els.projectMeldStatus.textContent = "select sessions";
  } else if (!els.projectMeldStatus.textContent) {
    els.projectMeldStatus.textContent = "";
  }
}

function renderProjectMeldOutput() {
  if (!state.projectMeld) {
    els.projectMeldOutput.hidden = true;
    els.projectMeldOutputTitle.textContent = "Project meld";
    els.projectMeldText.textContent = "";
    return;
  }
  const output = state.projectMeld.executionMode === "direct"
    ? state.projectMeld.preview
    : state.projectMeld.bundle;
  const notes = Array.isArray(state.projectMeld.degradationNotes) && state.projectMeld.degradationNotes.length
    ? `\n\nDegradation notes:\n${state.projectMeld.degradationNotes.map(note => `- ${note}`).join("\n")}`
    : "";
  els.projectMeldOutput.hidden = false;
  els.projectMeldOutputTitle.textContent = state.projectMeld.executionMode === "direct"
    ? "Direct meld preview"
    : "Exportable meld bundle";
  els.projectMeldText.textContent = `${output || ""}${notes}`;
}

async function previewProjectMeld() {
  if (!state.activeProjectKey || !state.selectedProjectSessionIds.size) return;
  const data = new FormData(els.projectMeldForm);
  const body = {
    sessionIds: [...state.selectedProjectSessionIds],
    executionMode: data.get("executionMode") || "export_bundle",
    provider: (data.get("provider") || "").trim(),
    model: (data.get("model") || "").trim(),
  };
  els.projectMeldPreviewButton.disabled = true;
  els.projectMeldStatus.textContent = body.executionMode === "direct" ? "running preview" : "building bundle";
  try {
    state.projectMeld = await api(`/api/projects/${encodeURIComponent(state.activeProjectKey)}/melds/preview`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    els.projectMeldStatus.textContent =
      `${num(state.projectMeld.sessionCount || 0)} sessions · ${num(state.projectMeld.evidenceCount || 0)} evidence · ${num(state.projectMeld.bundleChars || 0)} chars`;
    renderProjectMeldOutput();
  } catch (err) {
    state.projectMeld = null;
    renderProjectMeldOutput();
    els.projectMeldStatus.textContent = `meld failed: ${err.message}`;
  } finally {
    renderProjectMeldTray();
  }
}

function renderProjectTimeline(timeline) {
  const items = timeline.items || [];
  const total = Number(timeline.count || items.length);
  els.projectTimelineMeta.textContent = `${num(items.length)} / ${num(total)} blocks`;
  if (!items.length) {
    els.projectTimeline.innerHTML = `<p class="empty">No storyline blocks for this project.</p>`;
    return;
  }
  els.projectTimeline.innerHTML = items.map(renderProjectBlock).join("");
}

function renderProjectBlock(block, i) {
  const type = block.blockType || "event";
  const src = (block.source || "unknown").toLowerCase();
  const headline = escapeHtml(tildify(block.headline || block.eventType || "Event"));
  const bodyText = block.text && block.text !== block.headline
    ? `<div class="project-block-text">${escapeHtml(clamp(tildify(block.text), 640))}</div>`
    : "";
  const session = block.sessionId
    ? `<button type="button" class="project-block-session" data-session-id="${escapeHtml(block.sessionId)}">${escapeHtml(block.sessionTitle || compactId(block.sessionId))}</button>`
    : "";
  return `<article class="project-block project-block--${escapeHtml(type)}" data-event-id="${escapeHtml(block.id)}" style="--i:${i}">
    <div class="project-block-head">
      <span class="project-block-type">${escapeHtml(type)}</span>
      <span class="src src--${escapeHtml(src)}">${escapeHtml(src)}</span>
      ${session}
      <span class="project-block-time">${escapeHtml(relativeTime(block.observedAt))}</span>
    </div>
    <div class="project-block-title">${headline}</div>
    ${bodyText}
    ${projectBlockDetails(block)}
  </article>`;
}

function projectBlockDetails(block) {
  const parts = [];
  if (block.toolName) parts.push(`tool ${block.toolName}`);
  if (block.toolInputJson) parts.push("input\n" + prettyJson(block.toolInputJson));
  if (block.toolOutputJson) parts.push("output\n" + prettyJson(block.toolOutputJson));
  if (block.metadata && Object.keys(block.metadata).length) {
    parts.push("metadata\n" + JSON.stringify(block.metadata, null, 2));
  }
  if (!parts.length) return "";
  return `<details class="project-block-raw"><summary>raw evidence</summary><pre>${escapeHtml(clamp(tildify(parts.join("\n\n")), 5000))}</pre></details>`;
}

// --------------------------------------------------------------- outline/toc
function renderSessionOutline(events) {
  const outline = buildSessionOutline(events);
  if (!events.length) {
    els.outlineBody.innerHTML = `<p class="empty">Open a session to build an outline.</p>`;
    return;
  }
  els.outlineBody.innerHTML = `
    <div class="session-minimap" aria-label="Event minimap">
      ${eventsForReading(events).map(renderMinimapTick).join("")}
    </div>
    ${outline.map(renderOutlineSection).join("")}`;
}

function buildSessionOutline(events) {
  const edited = uniqueOutlineItems(events.flatMap(event => fileOutlineItems(event, "edited")));
  const read = uniqueOutlineItems(events.flatMap(event => fileOutlineItems(event, "read")));
  const tools = [...toolCounts(events)].map(([tool, count]) => ({
    label: tool,
    meta: `${num(count)} uses`,
    eventId: firstEventForTool(events, tool)?.id || "",
  }));
  const eventKinds = Object.entries(events.reduce((acc, event) => {
    const kind = classify(event);
    acc[kind] = (acc[kind] || 0) + 1;
    return acc;
  }, {})).map(([kind, count]) => ({
    label: kind,
    meta: `${num(count)} events`,
    eventId: events.find(event => classify(event) === kind)?.id || "",
  }));
  return [
    { key: "edited", title: "Files edited", items: edited },
    { key: "read", title: "Files read", items: read },
    { key: "tools", title: "Tools used", items: tools },
    { key: "events", title: "Event shape", items: eventKinds },
  ];
}

function eventsForReading(events) {
  return [...events].reverse();
}

function renderMinimapTick(event) {
  const cls = classify(event);
  const title = eventSummary(event, cls).text;
  return `<button type="button" class="minimap-tick minimap-tick--${escapeHtml(cls)}" data-event-id="${escapeHtml(event.id)}" title="${escapeHtml(tildify(title))}"></button>`;
}

function renderOutlineSection(section) {
  const visible = section.items.slice(0, 5);
  const overflow = Math.max(0, section.items.length - visible.length);
  const rows = visible.length
    ? visible.map(renderOutlineItem).join("")
    : `<p class="outline-empty">None</p>`;
  return `<section class="outline-section" data-outline-section="${escapeHtml(section.key)}">
    <button type="button" class="outline-section-head" data-outline-popout="${escapeHtml(section.key)}">
      <span>${escapeHtml(section.title)}</span>
      <strong>${num(section.items.length)}</strong>
    </button>
    <div class="outline-list">${rows}</div>
    ${overflow ? `<button type="button" class="outline-more" data-outline-popout="${escapeHtml(section.key)}">${num(overflow)} more</button>` : ""}
  </section>`;
}

function renderOutlineItem(item) {
  const target = item.eventId ? ` data-event-id="${escapeHtml(item.eventId)}"` : "";
  return `<button type="button" class="outline-item"${target}>
    <span>${escapeHtml(tildify(item.label))}</span>
    <small>${escapeHtml(item.meta || "")}</small>
  </button>`;
}

function fileOutlineItems(event, mode) {
  if (!event.toolName) return [];
  const tool = event.toolName.toLowerCase();
  const edited = /multiedit|edit|write|patch/.test(tool);
  const read = /read|grep|search|glob|list|ls|cat|sed/.test(tool);
  if (mode === "edited" && !edited) return [];
  if (mode === "read" && !read) return [];
  return toolPaths(event).map(path => ({
    label: path,
    meta: event.toolName,
    eventId: event.id,
  }));
}

function toolPaths(event) {
  const args = parseToolArgs(event.toolInputJson);
  if (!args) return [];
  const paths = [];
  for (const key of ["file_path", "filePath", "target_file", "targetFile", "path", "cwd"]) {
    if (typeof args[key] === "string" && args[key].trim()) paths.push(args[key].trim());
  }
  if (Array.isArray(args.files)) {
    for (const file of args.files) {
      if (typeof file === "string" && file.trim()) paths.push(file.trim());
    }
  }
  const primary = primaryArgKey(args);
  if (!paths.length && primary && /path|file/i.test(primary)) {
    paths.push(String(args[primary]).trim());
  }
  return paths;
}

function uniqueOutlineItems(items) {
  const seen = new Set();
  const unique = [];
  for (const item of items) {
    const key = normalizeSearch(`${item.label}\u0000${item.meta}`);
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(item);
  }
  return unique;
}

function firstEventForTool(events, tool) {
  return eventsForReading(events).find(event => event.toolName === tool);
}

function openOutlinePopout(sectionKey) {
  const section = buildSessionOutline(state.activeEvents).find(item => item.key === sectionKey);
  if (!section) return;
  els.outlinePopoutTitle.textContent = section.title;
  els.outlinePopoutBody.innerHTML = section.items.length
    ? section.items.map(renderOutlineItem).join("")
    : `<p class="empty">No matching outline entries.</p>`;
  els.outlinePopout.hidden = false;
  els.outlinePopoutCloseButton.focus();
}

function closeOutlinePopout() {
  els.outlinePopout.hidden = true;
}

function applyRailState() {
  els.deck.classList.toggle("rail-collapsed", state.railCollapsed);
  els.projectRail.classList.toggle("collapsed", state.railCollapsed);
  els.toggleRailButton.textContent = state.railCollapsed ? "Open" : "Rail";
  els.toggleRailButton.title = state.railCollapsed ? "Expand sidebar" : "Collapse sidebar";
  els.toggleRailButton.setAttribute("aria-label", state.railCollapsed ? "Expand sidebar" : "Collapse sidebar");
  els.toggleRailButton.setAttribute("aria-expanded", String(!state.railCollapsed));
}

function toggleRail() {
  state.railCollapsed = !state.railCollapsed;
  saveBoolean(RAIL_COLLAPSED_STORAGE_KEY, state.railCollapsed);
  applyRailState();
}

function applyOutlineState() {
  els.sessionOutline.classList.toggle("collapsed", state.outlineCollapsed);
  els.toggleOutlineButton.textContent = state.outlineCollapsed ? "Open" : "TOC";
  els.toggleOutlineButton.title = state.outlineCollapsed ? "Expand outline" : "Collapse outline";
  els.toggleOutlineButton.setAttribute("aria-label", state.outlineCollapsed ? "Expand outline" : "Collapse outline");
  els.toggleOutlineButton.setAttribute("aria-expanded", String(!state.outlineCollapsed));
}

function toggleOutline() {
  state.outlineCollapsed = !state.outlineCollapsed;
  saveBoolean(OUTLINE_COLLAPSED_STORAGE_KEY, state.outlineCollapsed);
  applyOutlineState();
}

function loadBoolean(key, fallback = false) {
  try {
    const value = window.localStorage?.getItem(key);
    if (value === "true") return true;
    if (value === "false") return false;
  } catch (_) { /* localStorage may be unavailable */ }
  return fallback;
}

function saveBoolean(key, value) {
  try {
    window.localStorage?.setItem(key, String(Boolean(value)));
  } catch (_) { /* localStorage may be unavailable */ }
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
  if (button) {
    activateTab(button.dataset.tab);
    if (button.dataset.tab === "projects") {
      loadProjects(false).catch(err => {
        els.projectsMeta.textContent = `projects failed: ${err.message}`;
      });
    }
  }
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

els.projectList.addEventListener("click", event => {
  const row = event.target.closest(".project-row[data-project-key]");
  if (row) {
    selectProject(row.dataset.projectKey).catch(err => {
      els.projectsMeta.textContent = `project failed: ${err.message}`;
    });
  }
});

els.projectSessions.addEventListener("click", async event => {
  const checkbox = event.target.closest("[data-project-session-check]");
  if (checkbox) {
    const sessionId = checkbox.dataset.projectSessionCheck;
    if (checkbox.checked) {
      state.selectedProjectSessionIds.add(sessionId);
    } else {
      state.selectedProjectSessionIds.delete(sessionId);
    }
    state.projectMeld = null;
    els.projectMeldStatus.textContent = "";
    renderProjectSessions(state.projectSessions);
    renderProjectMeldTray();
    renderProjectMeldOutput();
    return;
  }
  const open = event.target.closest("[data-open-project-session]");
  if (!open) return;
  await selectSession(open.dataset.openProjectSession);
  activateTab("spine");
});

els.projectMeldForm.addEventListener("submit", event => {
  event.preventDefault();
  previewProjectMeld();
});

els.copyProjectMeldButton.addEventListener("click", async () => {
  if (!els.projectMeldText.textContent) return;
  await copyValue(els.projectMeldText.textContent, els.copyProjectMeldButton);
});

els.projectTimeline.addEventListener("click", async event => {
  const row = event.target.closest(".project-block-session[data-session-id]");
  if (!row) return;
  await selectSession(row.dataset.sessionId);
  activateTab("spine");
});

els.sessionSearchInput.addEventListener("input", event => {
  state.sessionQuery = event.currentTarget.value;
  renderSessionsRail();
});

els.toggleRailButton.addEventListener("click", toggleRail);
els.expandAllSessionsButton.addEventListener("click", expandAllSessionGroups);
els.collapseAllSessionsButton.addEventListener("click", collapseAllSessionGroups);

els.sessionIdentity.addEventListener("click", event => {
  const button = event.target.closest(".id-chip[data-copy-value]");
  if (button) copyValue(button.dataset.copyValue, button);
});

els.refreshButton.addEventListener("click", async () => {
  try { await Promise.all([loadStatus(), loadAskStatus(), loadSessions(false), loadProjects(false)]); }
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
    await Promise.all([loadStatus(true), loadSessions(true), loadProjects(false)]);
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

els.summaryOpenButton.addEventListener("click", openSummaryDialog);
els.summaryCloseButton.addEventListener("click", closeSummaryDialog);
els.summaryDialog.addEventListener("click", event => {
  if (event.target === els.summaryDialog) closeSummaryDialog();
});

els.toggleOutlineButton.addEventListener("click", toggleOutline);
els.outlineBody.addEventListener("click", event => {
  const popout = event.target.closest("[data-outline-popout]");
  if (popout) {
    openOutlinePopout(popout.dataset.outlinePopout);
    return;
  }
  const minimap = event.target.closest(".minimap-tick[data-event-id]");
  const item = event.target.closest(".outline-item[data-event-id]");
  const target = minimap || item;
  if (target) locateOnSpine(target.dataset.eventId);
});
els.outlinePopout.addEventListener("click", event => {
  if (event.target === els.outlinePopout) closeOutlinePopout();
  const item = event.target.closest(".outline-item[data-event-id]");
  if (item) {
    closeOutlinePopout();
    locateOnSpine(item.dataset.eventId);
  }
});
els.outlinePopoutCloseButton.addEventListener("click", closeOutlinePopout);

els.spine.addEventListener("click", async event => {
  const nodeToggle = event.target.closest(".node-summary[data-toggle-event]");
  if (nodeToggle) {
    toggleNode(nodeToggle.dataset.toggleEvent);
    return;
  }
  const copy = event.target.closest(".tool-card-button[data-copy-kind]");
  if (copy) {
    event.preventDefault();
    const value = toolCopyValue(copy.dataset.eventId, copy.dataset.copyKind);
    if (value != null) await copyValue(value, copy);
    return;
  }
  const toggle = event.target.closest(".tool-card-button[data-expand-target]");
  if (toggle) {
    const card = toggle.closest(".tool-card");
    const block = card?.querySelector(`[data-expandable="${toggle.dataset.expandTarget}"]`);
    if (block) {
      const clamped = block.classList.toggle("clamped");
      toggle.textContent = clamped ? "Expand" : "Collapse";
    }
  }
});

window.addEventListener("keydown", event => {
  if (event.key !== "Escape") return;
  if (!els.summaryDialog.hidden) closeSummaryDialog();
  if (!els.outlinePopout.hidden) closeOutlinePopout();
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
    <div class="result-foot">${escapeHtml(event.eventType || "Event")}${where ? ` · ${escapeHtml(tildify(where))}` : ""}</div>
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
    renderSummaryCard(session);
    setSummaryExportState(session);
    await loadSessions(false);
  } catch (err) {
    els.summary.hidden = false;
    els.summaryPreview.textContent = `Summarize failed: ${err.message}`;
    els.summaryDialogText.textContent = `Summarize failed: ${err.message}`;
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
state.railCollapsed = loadBoolean(RAIL_COLLAPSED_STORAGE_KEY, false);
state.outlineCollapsed = loadBoolean(OUTLINE_COLLAPSED_STORAGE_KEY, false);
applyRailState();
applyOutlineState();

Promise.all([loadStatus(), loadAskStatus(), loadExportTargets(), loadProjects(false), loadSessions(true)])
  .catch(error => {
  els.readouts.innerHTML = gauge("recorder", "degraded", "unreachable");
  els.spine.innerHTML = `<p class="empty">Could not reach the recorder: ${escapeHtml(error.message)}</p>`;
});
