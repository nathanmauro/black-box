import { For, Show } from "solid-js";
import type { AgentProcess } from "../../lib/sse";
import "./ProcessPanel.css";

type ProcessPanelProps = {
  processes: AgentProcess[];
  available: boolean;
};

const AGENT_COLORS: Record<string, string> = {
  claude: "#A66EE8",
  codex: "#4A90E2",
  cursor: "#000000",
  raycast: "#FF6363",
};

function formatMemory(kb: number): string {
  if (kb < 1024) return `${kb} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(0)} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(1)} GB`;
}

export default function ProcessPanel(props: ProcessPanelProps) {
  return (
    <div class="process-panel">
      <Show
        when={props.available}
        fallback={
          <div class="process-unavailable">Process monitoring unavailable</div>
        }
      >
        <Show
          when={props.processes.length > 0}
          fallback={<div class="process-empty">No agent processes running</div>}
        >
          <div class="process-list">
            <For each={props.processes}>
              {(proc) => (
                <div class="process-row">
                  <div class="process-agent">
                    <span
                      class="agent-dot"
                      style={{ "background-color": AGENT_COLORS[proc.agent] || "#999" }}
                    />
                    <span class="agent-name">{proc.agent}</span>
                    <span class="process-pid">({proc.pid})</span>
                  </div>
                  <div class="process-metrics">
                    <div class="metric">
                      <span class="metric-label">CPU</span>
                      <span class="metric-value">{proc.cpuPercent.toFixed(1)}%</span>
                      <div class="cpu-bar">
                        <div
                          class="cpu-bar-fill"
                          style={{ width: `${Math.min(proc.cpuPercent, 100)}%` }}
                        />
                      </div>
                    </div>
                    <div class="metric">
                      <span class="metric-label">Memory</span>
                      <span class="metric-value">{formatMemory(proc.rssKb)}</span>
                    </div>
                    <div class="metric">
                      <span class="metric-label">Uptime</span>
                      <span class="metric-value">{proc.elapsed}</span>
                    </div>
                  </div>
                </div>
              )}
            </For>
          </div>
        </Show>
      </Show>
    </div>
  );
}
