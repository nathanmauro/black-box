// Black Box — instrument logic. Reads the local recorder over /api/*, draws the Cognition Spine,
// and fires the recall beam. No framework, no build step. The screen is an instrument, not a page.

const state = {
  sessions: [],
  activeSessionId: null,
  spineEventIds: new Set(),
};

const els = {
  readouts: document.querySelector("#readouts"),
  sessions: document.querySelector("#sessions"),
  sessionCount: document.querySelector("#sessionCount"),
  refreshButton: document.querySelector("#refreshButton"),
  captureForm: document.querySelector("#captureForm"),
  searchForm: document.querySelector("#searchForm"),
  searchResults: document.querySelector("#searchResults"),
  searchMeta: document.querySelector("#searchMeta"),
  recallForm: document.querySelector("#recallForm"),
  recallMeta: document.querySelector("#recallMeta"),
  recallStage: document.querySelector("#recallStage"),
  memoryCards: document.querySelector("#memoryCards"),
  cone: document.querySelector("#cone"),
  spine: document.querySelector("#spine"),
  spineTitle: document.querySelector("#spineTitle"),
  spineMeta: document.querySelector("#spineMeta"),
  summary: document.querySelector("#summary"),
  summarizeButton: document.querySelector("#summarizeButton"),
};

const SVG_NS = "http://www.w3.org/2000/svg";

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
    els.sessions.innerHTML = `<p class="empty">No sessions yet. Run ./scripts/demo.sh or connect a hook.</p>`;
    els.spine.innerHTML = `<p class="empty">The recorder is idle. Capture an event to lay down the first trace.</p>`;
    return;
  }
  els.sessions.innerHTML = state.sessions.map(session => {
    const src = (session.source || "unknown").toLowerCase();
    const aiTitled = session.summary ? `<span class="seal">◆ ai</span>` : "";
    return `<button class="channel ${session.id === state.activeSessionId ? "active" : ""}" data-id="${escapeHtml(session.id)}">
      <span class="channel-title">${escapeHtml(session.title)} ${aiTitled}</span>
      <span class="channel-meta">
        <span class="src src--${escapeHtml(src)}">${escapeHtml(src)}</span>
        <span>${num(session.eventCount)} ev</span>
        <span>·</span>
        <span>${escapeHtml(relativeTime(session.lastSeenAt))}</span>
      </span>
    </button>`;
  }).join("");
  if (selectFirst || !state.activeSessionId || !state.sessions.some(s => s.id === state.activeSessionId)) {
    await selectSession(state.sessions[0].id);
  }
}

async function selectSession(sessionId) {
  state.activeSessionId = sessionId;
  const session = state.sessions.find(s => s.id === sessionId);
  if (session) {
    els.spineTitle.textContent = session.title;
    const where = session.cwd ? ` · ${session.cwd}` : "";
    els.spineMeta.textContent = `${session.source} · ${session.clientSessionId}${where} · ${session.eventCount} events`;
    els.summary.hidden = !session.summary;
    els.summary.textContent = session.summary || "";
  }
  els.summarizeButton.disabled = false;
  els.sessions.querySelectorAll(".channel").forEach(row =>
    row.classList.toggle("active", row.dataset.id === sessionId));
  const events = await api(`/api/sessions/${encodeURIComponent(sessionId)}/events?limit=120`);
  renderSpine(events);
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
    els.spine.innerHTML = `<p class="empty">No events in this session.</p>`;
    return;
  }
  // Oldest-first reads like a trace laid down over time; the API returns newest-first.
  const ordered = [...events].reverse();
  els.spine.innerHTML = ordered.map((event, i) => renderNode(event, i)).join("");
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

// ----------------------------------------------------------------- recall
async function doRecall(scope, withinHours) {
  els.recallMeta.textContent = "reading…";
  const params = new URLSearchParams();
  if (scope) params.set("scope", scope);
  params.set("withinHours", String(withinHours));
  params.set("kinds", "decision,handoff");
  const result = await api(`/api/recall?${params.toString()}`);

  els.recallStage.hidden = false;
  els.cone.innerHTML = "";
  const scopeLabel = result.scope ? `“${result.scope}”` : "all repos";
  els.recallMeta.textContent = `${result.count} recalled · ${scopeLabel}`;

  if (!result.items.length) {
    els.memoryCards.innerHTML = `<p class="recall-empty">No prior intent committed for ${escapeHtml(scopeLabel)} yet — capture a decision, then recall it back.</p>`;
    return;
  }
  els.memoryCards.innerHTML = result.items.map((item, i) => renderMemory(item, i)).join("");
  wireMemoryCards();
  // Draw the cone after layout settles so card geometry is final.
  requestAnimationFrame(() => requestAnimationFrame(drawCone));
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

// Click a recalled memory to find it on the active Spine — the coordination edge, made tangible.
function wireMemoryCards() {
  els.memoryCards.querySelectorAll(".mem").forEach(card => {
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

// The signature: an amber cone of light fanning from the recall console to each surfaced memory.
function drawCone() {
  const cards = [...els.memoryCards.querySelectorAll(".mem")];
  els.cone.innerHTML = "";
  if (!cards.length) return;
  const stage = els.recallStage.getBoundingClientRect();
  const originX = stage.width / 2;
  const originY = 2;
  const emitter = document.createElementNS(SVG_NS, "circle");
  emitter.setAttribute("cx", String(originX));
  emitter.setAttribute("cy", String(originY));
  emitter.setAttribute("r", "3");
  els.cone.appendChild(emitter);
  for (const card of cards) {
    const r = card.getBoundingClientRect();
    const tx = r.left - stage.left + r.width / 2;
    const ty = r.top - stage.top;
    const midY = (originY + ty) / 2;
    const d = `M ${originX} ${originY} C ${originX} ${midY}, ${tx} ${midY}, ${tx} ${ty}`;
    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("d", d);
    const dx = tx - originX, dy = ty - originY;
    const len = Math.round(Math.hypot(dx, dy) * 1.35);
    path.style.setProperty("--len", String(len));
    els.cone.appendChild(path);
  }
}

// ----------------------------------------------------------------- actions
els.sessions.addEventListener("click", event => {
  const row = event.target.closest(".channel");
  if (row) selectSession(row.dataset.id);
});

els.refreshButton.addEventListener("click", async () => {
  try { await Promise.all([loadStatus(), loadSessions(false)]); }
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
    await loadSessions(false);
  } catch (err) {
    els.summary.hidden = false;
    els.summary.textContent = `Summarize failed: ${err.message}`;
  } finally {
    button.textContent = original;
    button.disabled = false;
  }
});

window.addEventListener("resize", () => {
  if (!els.recallStage.hidden && els.memoryCards.children.length) drawCone();
});

// ----------------------------------------------------------------- boot
Promise.all([loadStatus(), loadSessions(true)]).catch(error => {
  els.readouts.innerHTML = gauge("recorder", "degraded", "unreachable");
  els.spine.innerHTML = `<p class="empty">Could not reach the recorder: ${escapeHtml(error.message)}</p>`;
});
