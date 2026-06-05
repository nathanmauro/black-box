(function () {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";
  const KINDS = ["decision", "handoff"];
  const CLUSTER_RADIUS = 168;
  const MEMBER_RADIUS = 126;
  const LABEL_HIDE_COUNT = 24;
  const ZOOM_LABEL_THRESHOLD = 0.74;
  const FIT_PADDING = 82;

  let graph = null;

  function render(container, items, ctx) {
    destroy();
    if (!container) return;

    const safeItems = Array.isArray(items) ? items : [];
    graph = {
      container,
      items: safeItems,
      ctx: ctx || {},
      clusters: buildClusters(safeItems),
      expanded: new Set(),
      nodes: [],
      edges: [],
      nodeMap: new Map(),
      selectedId: null,
      hoverId: null,
      viewBox: [0, 0, 1, 1],
      width: 1,
      height: 1,
      dragging: null,
      abort: new AbortController(),
      animationTimer: null,
    };

    if (graph.clusters.length === 1) graph.expanded.add(graph.clusters[0].id);

    container.innerHTML = "";
    container.classList.add("constellation", "constellation--animate");
    container.classList.remove("constellation--crowded", "constellation--zoomed-out", "is-panning");

    if (!safeItems.length) {
      const empty = document.createElement("p");
      empty.className = "recall-empty";
      empty.textContent = `No prior intent committed for ${graph.ctx.scopeLabel || "all repos"} yet — capture a decision, then recall it back.`;
      container.appendChild(empty);
      return;
    }

    graph.svg = el("svg", {
      class: "constellation-svg",
      role: "img",
      "aria-label": `Recall constellation for ${graph.ctx.scopeLabel || "all repos"}`,
    });
    graph.edgeLayer = el("g", { class: "constellation-edges" });
    graph.sweepLayer = el("g", { class: "constellation-sweeps" });
    graph.nodeLayer = el("g", { class: "constellation-nodes" });
    graph.svg.append(graph.edgeLayer, graph.sweepLayer, graph.nodeLayer);
    container.appendChild(graph.svg);

    graph.fitButton = document.createElement("button");
    graph.fitButton.type = "button";
    graph.fitButton.className = "constellation-fit";
    graph.fitButton.title = "Fit graph";
    graph.fitButton.setAttribute("aria-label", "Fit graph");
    graph.fitButton.textContent = "⌖";
    container.appendChild(graph.fitButton);

    graph.tooltip = document.createElement("div");
    graph.tooltip.className = "constellation-tooltip";
    graph.tooltip.hidden = true;
    container.appendChild(graph.tooltip);

    draw(true);
    fit();
    wire();

    graph.animationTimer = window.setTimeout(() => {
      if (graph?.container) graph.container.classList.remove("constellation--animate");
    }, 1400);
  }

  function redraw() {
    if (!graph || !graph.svg) return;
    draw(false);
    fit();
  }

  function destroy() {
    if (!graph) return;
    if (graph.animationTimer) window.clearTimeout(graph.animationTimer);
    graph.abort.abort();
    graph.container.innerHTML = "";
    graph.container.classList.remove(
      "constellation--animate",
      "constellation--crowded",
      "constellation--zoomed-out",
      "is-panning"
    );
    graph = null;
  }

  function wire() {
    const signal = graph.abort.signal;
    graph.fitButton.addEventListener("click", fit, { signal });
    graph.svg.addEventListener("wheel", onWheel, { passive: false, signal });
    graph.svg.addEventListener("pointerdown", onPointerDown, { signal });
    graph.svg.addEventListener("pointermove", onPointerMove, { signal });
    graph.svg.addEventListener("pointerup", onPointerUp, { signal });
    graph.svg.addEventListener("pointerleave", onPointerLeave, { signal });
    graph.svg.addEventListener("mouseover", onPointerOver, { signal });
    graph.svg.addEventListener("mouseout", onPointerOut, { signal });
    graph.svg.addEventListener("click", onClick, { signal });
    graph.svg.addEventListener("keydown", onKeyDown, { signal });
  }

  function draw(withSweep) {
    measure();
    const layout = layoutGraph();
    graph.nodes = layout.nodes;
    graph.edges = layout.edges;
    graph.bounds = layout.bounds;
    graph.nodeMap = new Map(graph.nodes.map(node => [node.id, node]));
    graph.container.classList.toggle("constellation--crowded", graph.nodes.length > LABEL_HIDE_COUNT);

    graph.edgeLayer.innerHTML = "";
    graph.sweepLayer.innerHTML = "";
    graph.nodeLayer.innerHTML = "";

    for (const edge of graph.edges) {
      const from = graph.nodeMap.get(edge.from);
      const to = graph.nodeMap.get(edge.to);
      if (!from || !to) continue;
      const line = el("line", {
        class: "constellation-edge",
        x1: from.x,
        y1: from.y,
        x2: to.x,
        y2: to.y,
        "data-edge-id": edge.id,
        "data-from": edge.from,
        "data-to": edge.to,
      });
      graph.edgeLayer.appendChild(line);
    }

    if (withSweep) drawSweep(layout.sweepRadius);

    graph.nodes.forEach((node, i) => {
      const g = el("g", {
        class: nodeClasses(node),
        transform: `translate(${round(node.x)} ${round(node.y)})`,
        "data-node-id": node.id,
        tabindex: node.type === "origin" ? "-1" : "0",
        role: node.type === "origin" ? "img" : "button",
        "aria-label": node.tooltip,
      });
      g.style.setProperty("--i", String(i));
      if (node.type === "cluster") g.setAttribute("aria-expanded", String(graph.expanded.has(node.id)));

      const title = el("title");
      title.textContent = node.tooltip;
      g.appendChild(title);
      g.appendChild(glyph(node));
      g.appendChild(label(node));
      graph.nodeLayer.appendChild(g);
    });

    setHover(graph.hoverId);
    setSelected(graph.selectedId);
  }

  function drawSweep(radius) {
    const sweep = el("g", { class: "constellation-sweep", "aria-hidden": "true" });
    sweep.appendChild(el("line", { x1: 0, y1: 0, x2: 0, y2: -radius }));
    sweep.appendChild(el("path", {
      d: `M ${-radius} 0 A ${radius} ${radius} 0 0 1 ${radius} 0`,
    }));
    graph.sweepLayer.appendChild(sweep);
  }

  function layoutGraph() {
    const nodes = [{
      id: "origin",
      type: "origin",
      kind: "origin",
      x: 0,
      y: 0,
      size: 24,
      label: graph.ctx.scopeLabel || "all repos",
      tooltip: `origin · ${graph.ctx.scopeLabel || "all repos"}`,
    }];
    const edges = [];
    const count = graph.clusters.length;
    const step = count ? (Math.PI * 2) / count : 0;
    const start = -Math.PI / 2;

    graph.clusters.forEach((cluster, clusterIndex) => {
      const angle = start + step * clusterIndex;
      const clusterNode = {
        id: cluster.id,
        type: "cluster",
        kind: cluster.kind,
        x: Math.cos(angle) * CLUSTER_RADIUS,
        y: Math.sin(angle) * CLUSTER_RADIUS,
        angle,
        size: 21,
        count: cluster.items.length,
        label: `${plural(cluster.kind)} · ${cluster.items.length}`,
        tooltip: `${plural(cluster.kind)} · ${cluster.items.length}`,
      };
      nodes.push(clusterNode);
      edges.push({ id: `edge:origin:${cluster.id}`, from: "origin", to: cluster.id });

      if (!graph.expanded.has(cluster.id)) return;

      const spread = memberSpread(cluster.items.length);
      const base = angle - spread / 2;
      cluster.items.forEach((item, memberIndex) => {
        const memberAngle = cluster.items.length === 1
          ? angle
          : base + (spread * memberIndex) / (cluster.items.length - 1);
        const id = memberId(cluster.kind, item, memberIndex);
        nodes.push({
          id,
          type: "member",
          kind: cluster.kind,
          item,
          x: clusterNode.x + Math.cos(memberAngle) * MEMBER_RADIUS,
          y: clusterNode.y + Math.sin(memberAngle) * MEMBER_RADIUS,
          angle: memberAngle,
          size: memberSize(item),
          label: clamp(item.headline || item.contextSummary || item.kind || "intent", 36),
          tooltip: tooltipFor(item),
          locatable: isOnSpine(item),
        });
        edges.push({ id: `edge:${cluster.id}:${id}`, from: cluster.id, to: id });
      });
    });

    const bounds = boundsFor(nodes);
    return { nodes, edges, bounds, sweepRadius: Math.max(CLUSTER_RADIUS + MEMBER_RADIUS + 26, 300) };
  }

  function buildClusters(items) {
    const byKind = new Map();
    for (const item of items) {
      const kind = normalizeKind(item.kind);
      if (!byKind.has(kind)) byKind.set(kind, []);
      byKind.get(kind).push(item);
    }
    const unknown = [...byKind.keys()].filter(kind => !KINDS.includes(kind)).sort();
    return [...KINDS, ...unknown]
      .filter(kind => byKind.has(kind))
      .map(kind => ({ id: `cluster:${kind}`, kind, items: byKind.get(kind) }));
  }

  function memberSpread(count) {
    if (count <= 1) return 0;
    return Math.min(Math.PI * 1.25, Math.max(Math.PI / 3, (count - 1) * 0.2));
  }

  function boundsFor(nodes) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const node of nodes) {
      const pad = node.type === "origin" ? 70 : node.type === "cluster" ? 122 : 148;
      minX = Math.min(minX, node.x - pad);
      minY = Math.min(minY, node.y - pad);
      maxX = Math.max(maxX, node.x + pad);
      maxY = Math.max(maxY, node.y + pad);
    }
    return { minX, minY, maxX, maxY };
  }

  function fit() {
    if (!graph || !graph.svg || !graph.bounds) return;
    measure();
    const bounds = graph.bounds;
    const graphWidth = Math.max(180, bounds.maxX - bounds.minX + FIT_PADDING);
    const graphHeight = Math.max(180, bounds.maxY - bounds.minY + FIT_PADDING);
    const ratio = graph.width / graph.height;
    let width = graphWidth;
    let height = graphHeight;

    if (width / height > ratio) height = width / ratio;
    else width = height * ratio;

    const cx = (bounds.minX + bounds.maxX) / 2;
    const cy = (bounds.minY + bounds.maxY) / 2;
    graph.viewBox = [cx - width / 2, cy - height / 2, width, height];
    applyViewBox();
  }

  function applyViewBox() {
    graph.svg.setAttribute("viewBox", graph.viewBox.map(round).join(" "));
    updateLod();
  }

  function updateLod() {
    const scale = graph.width / graph.viewBox[2];
    graph.container.classList.toggle("constellation--zoomed-out", scale < ZOOM_LABEL_THRESHOLD);
  }

  function onWheel(event) {
    if (!graph?.svg) return;
    event.preventDefault();
    const point = pointFromEvent(event);
    const factor = event.deltaY > 0 ? 1.14 : 0.88;
    const current = graph.viewBox;
    const nextWidth = clampNumber(current[2] * factor, 150, 1800);
    const nextHeight = nextWidth / (graph.width / graph.height);
    const rx = (point.x - current[0]) / current[2];
    const ry = (point.y - current[1]) / current[3];
    graph.viewBox = [
      point.x - rx * nextWidth,
      point.y - ry * nextHeight,
      nextWidth,
      nextHeight,
    ];
    applyViewBox();
  }

  function onPointerDown(event) {
    if (event.button !== 0 || closestNode(event.target)) return;
    graph.dragging = {
      x: event.clientX,
      y: event.clientY,
      viewBox: [...graph.viewBox],
      moved: false,
    };
    graph.container.classList.add("is-panning");
    graph.svg.setPointerCapture(event.pointerId);
  }

  function onPointerMove(event) {
    if (graph.dragging) {
      const dx = event.clientX - graph.dragging.x;
      const dy = event.clientY - graph.dragging.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) graph.dragging.moved = true;
      graph.viewBox = [
        graph.dragging.viewBox[0] - dx * graph.dragging.viewBox[2] / graph.width,
        graph.dragging.viewBox[1] - dy * graph.dragging.viewBox[3] / graph.height,
        graph.dragging.viewBox[2],
        graph.dragging.viewBox[3],
      ];
      applyViewBox();
      return;
    }
    moveTooltip(event);
  }

  function onPointerUp(event) {
    if (!graph.dragging) return;
    graph.suppressClick = graph.dragging.moved;
    graph.dragging = null;
    graph.container.classList.remove("is-panning");
    graph.svg.releasePointerCapture(event.pointerId);
    window.setTimeout(() => { if (graph) graph.suppressClick = false; }, 0);
  }

  function onPointerLeave() {
    if (!graph) return;
    setHover(null);
    if (graph.dragging) {
      graph.dragging = null;
      graph.container.classList.remove("is-panning");
    }
  }

  function onPointerOver(event) {
    const target = closestNode(event.target);
    if (!target) return;
    setHover(target.dataset.nodeId);
    moveTooltip(event);
  }

  function onPointerOut(event) {
    const target = closestNode(event.target);
    if (!target || target.contains(event.relatedTarget)) return;
    setHover(null);
  }

  function onClick(event) {
    if (graph.suppressClick) return;
    const target = closestNode(event.target);
    if (!target) return;
    activateNode(target.dataset.nodeId);
  }

  function onKeyDown(event) {
    if (event.key !== "Enter" && event.key !== " ") return;
    const target = closestNode(event.target);
    if (!target) return;
    event.preventDefault();
    activateNode(target.dataset.nodeId);
  }

  function activateNode(nodeId) {
    const node = graph.nodeMap.get(nodeId);
    if (!node) return;
    if (node.type === "cluster") {
      if (graph.expanded.has(node.id)) graph.expanded.delete(node.id);
      else graph.expanded.add(node.id);
      draw(false);
      fit();
      return;
    }
    if (node.type === "member") {
      graph.selectedId = node.id;
      setSelected(node.id);
      if (typeof graph.ctx.onSelect === "function") graph.ctx.onSelect(node.item);
    }
  }

  function setHover(nodeId) {
    graph.hoverId = nodeId;
    const hot = new Set([nodeId]);
    for (const edge of graph.edges) {
      if (edge.from === nodeId) hot.add(edge.to);
      if (edge.to === nodeId) hot.add(edge.from);
    }

    graph.svg.querySelectorAll(".constellation-node").forEach(node => {
      const active = node.dataset.nodeId === nodeId;
      const related = hot.has(node.dataset.nodeId);
      node.classList.toggle("is-hot", active);
      node.classList.toggle("is-dim", Boolean(nodeId) && !related);
    });
    graph.svg.querySelectorAll(".constellation-edge").forEach(edge => {
      const active = edge.dataset.from === nodeId || edge.dataset.to === nodeId;
      edge.classList.toggle("is-hot", active);
      edge.classList.toggle("is-dim", Boolean(nodeId) && !active);
    });

    if (!nodeId || !graph.tooltip) {
      if (graph.tooltip) graph.tooltip.hidden = true;
      return;
    }
    const node = graph.nodeMap.get(nodeId);
    graph.tooltip.textContent = node?.tooltip || "";
    graph.tooltip.hidden = false;
  }

  function setSelected(nodeId) {
    graph.svg.querySelectorAll(".constellation-node").forEach(node => {
      node.classList.toggle("is-selected", node.dataset.nodeId === nodeId);
    });
  }

  function moveTooltip(event) {
    if (!graph.tooltip || graph.tooltip.hidden) return;
    const rect = graph.container.getBoundingClientRect();
    graph.tooltip.style.left = `${event.clientX - rect.left + 12}px`;
    graph.tooltip.style.top = `${event.clientY - rect.top + 12}px`;
  }

  function pointFromEvent(event) {
    const rect = graph.svg.getBoundingClientRect();
    const [x, y, w, h] = graph.viewBox;
    return {
      x: x + ((event.clientX - rect.left) / rect.width) * w,
      y: y + ((event.clientY - rect.top) / rect.height) * h,
    };
  }

  function measure() {
    const rect = graph.container.getBoundingClientRect();
    graph.width = Math.max(320, rect.width || graph.container.clientWidth || 720);
    graph.height = Math.max(320, rect.height || graph.container.clientHeight || 430);
  }

  function glyph(node) {
    if (node.type === "origin") {
      const group = el("g", { class: "constellation-origin-glyph" });
      group.appendChild(el("circle", { class: "constellation-origin-ring", r: node.size / 2 }));
      group.appendChild(el("circle", { class: "constellation-origin-core", r: node.size / 4 }));
      return group;
    }
    return el("rect", {
      class: "constellation-glyph",
      x: -node.size / 2,
      y: -node.size / 2,
      width: node.size,
      height: node.size,
      transform: "rotate(45)",
    });
  }

  function label(node) {
    const place = labelPlace(node);
    const text = el("text", {
      class: `constellation-label constellation-label--${node.type}`,
      x: place.x,
      y: place.y,
      "text-anchor": place.anchor,
    });
    text.textContent = node.label;
    return text;
  }

  function labelPlace(node) {
    if (node.type === "origin") return { x: 0, y: -22, anchor: "middle" };
    const angle = node.angle || 0;
    const dx = Math.cos(angle);
    const dy = Math.sin(angle);
    const offset = node.size + 12;
    if (Math.abs(dx) < 0.28) {
      return { x: 0, y: dy < 0 ? -offset : offset + 7, anchor: "middle" };
    }
    return {
      x: dx > 0 ? offset : -offset,
      y: 4,
      anchor: dx > 0 ? "start" : "end",
    };
  }

  function nodeClasses(node) {
    const classes = [
      "constellation-node",
      `constellation-node--${node.type}`,
      `constellation-node--${node.kind}`,
    ];
    if (node.type === "cluster" && graph.expanded.has(node.id)) classes.push("is-expanded");
    if (node.type === "member" && node.locatable) classes.push("is-locatable");
    if (node.id === graph.selectedId) classes.push("is-selected");
    return classes.join(" ");
  }

  function closestNode(target) {
    return target?.closest ? target.closest(".constellation-node") : null;
  }

  function isOnSpine(item) {
    return Boolean(item?.eventId && graph.ctx.spineEventIds?.has?.(item.eventId));
  }

  function memberSize(item) {
    const n = Number(item?.confidence);
    if (!Number.isFinite(n)) return 12;
    return clampNumber(9 + clampNumber(n, 0, 1) * 9, 9, 18);
  }

  function tooltipFor(item) {
    const parts = [
      item.kind || "intent",
      item.source || "unknown",
      item.confidence != null ? `conf ${formatConf(item.confidence)}` : "",
    ].filter(Boolean);
    return `${parts.join(" · ")}\n${clamp(item.headline || item.contextSummary || "(untitled)", 110)}`;
  }

  function memberId(kind, item, index) {
    return `member:${kind}:${item.eventId || index}`;
  }

  function normalizeKind(kind) {
    const value = String(kind || "intent").toLowerCase();
    if (value === "decisions") return "decision";
    if (value === "handoffs") return "handoff";
    return value || "intent";
  }

  function plural(kind) {
    if (kind === "decision") return "decisions";
    if (kind === "handoff") return "handoffs";
    return kind.endsWith("s") ? kind : `${kind}s`;
  }

  function el(name, attrs = {}) {
    const node = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, String(value));
    return node;
  }

  function clamp(value, max) {
    const text = String(value ?? "");
    return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
  }

  function clampNumber(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function formatConf(value) {
    const n = Number(value);
    return Number.isFinite(n) ? n.toFixed(2) : String(value);
  }

  function round(value) {
    return Math.round(value * 100) / 100;
  }

  window.BlackBoxConstellation = { render, redraw, destroy };
})();
