import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readlinkSync, realpathSync, statSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

export type ProtectedRuntimeSnapshot = {
  listenerPids: number[];
  databasePath: string | null;
  databaseIdentity: string | null;
  syntheticEventRows: number | null;
};

export function assertIsolatedDatabase(dbPath: string, tempDir: string): void {
  if (!dbPath || !tempDir) throw new Error("The E2E database and temp directory must be explicit");
  const resolvedTemp = realpathSync(tempDir);
  const resolvedDbDirectory = realpathSync(path.dirname(dbPath));
  const resolvedDb = path.join(resolvedDbDirectory, path.basename(dbPath));
  const systemTemp = realpathSync(os.tmpdir());
  if (!resolvedTemp.startsWith(`${systemTemp}${path.sep}`)) {
    throw new Error(`Refusing E2E database outside the system temp directory: ${resolvedDb}`);
  }
  if (!path.basename(resolvedTemp).startsWith("black-box-saga-e2e-")) {
    throw new Error(`Refusing unrecognized E2E temp directory: ${resolvedTemp}`);
  }
  if (resolvedDbDirectory !== resolvedTemp || path.basename(resolvedDb) !== "black-box-saga-e2e.db") {
    throw new Error(`Refusing non-isolated E2E database path: ${resolvedDb}`);
  }
}

export function captureProtectedRuntime(explicitDatabasePath?: string | null): ProtectedRuntimeSnapshot {
  const listenerPids = listenerPidsOnPort(8766);
  const databasePath = explicitDatabasePath
    || process.env.SBA_PRODUCTION_DB_PATH
    || discoverDatabasePath(listenerPids)
    || null;
  return {
    listenerPids,
    databasePath,
    databaseIdentity: databasePath ? databaseIdentity(databasePath) : null,
    syntheticEventRows: databasePath ? syntheticEventRows(databasePath) : null,
  };
}

export function assertProtectedRuntimeUnchanged(
  before: ProtectedRuntimeSnapshot,
  after: ProtectedRuntimeSnapshot,
): void {
  if (before.listenerPids.join(",") !== after.listenerPids.join(",")) {
    throw new Error(`Production port 8766 listener changed during E2E: ${before.listenerPids} -> ${after.listenerPids}`);
  }
  if (before.databasePath !== after.databasePath || before.databaseIdentity !== after.databaseIdentity) {
    throw new Error(`Production database identity changed during E2E: ${before.databasePath || "unknown"}`);
  }
  // A null count means the measurement itself failed, not that rows appeared —
  // reporting it as a leak sends whoever reads the failure hunting for phantom rows.
  const measuredBoth = before.syntheticEventRows !== null && after.syntheticEventRows !== null;
  if ((before.syntheticEventRows === null) !== (after.syntheticEventRows === null)) {
    throw new Error(
      `Could not verify the production database against synthetic E2E leaks: `
        + `row count unmeasurable on one side (${before.syntheticEventRows} -> ${after.syntheticEventRows})`,
    );
  }
  if (measuredBoth && before.syntheticEventRows !== after.syntheticEventRows) {
    throw new Error(
      `Synthetic E2E events leaked into the production database: ${before.syntheticEventRows} -> ${after.syntheticEventRows}`,
    );
  }
}

/**
 * Env for any harness process that may start or address tmux sessions. Pinning TMUX_TMPDIR to
 * the run-private socket directory keeps the whole run on a private tmux server, so harness env
 * (SBA_BASE_URL, the isolated datasource) never becomes the global environment of the shared
 * default server that real runner workers later inherit from.
 */
export function privateTmuxEnv(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  const tmuxTmpDir = process.env.SBA_E2E_TMUX_TMPDIR;
  if (!tmuxTmpDir) {
    throw new Error("SBA_E2E_TMUX_TMPDIR must be set before the E2E harness touches tmux");
  }
  return { ...process.env, ...overrides, TMUX_TMPDIR: tmuxTmpDir };
}

export function safetySnapshotPath(tempDir: string): string {
  return path.join(tempDir, "protected-runtime-before.json");
}

export function writeSafetySnapshot(file: string, snapshot: ProtectedRuntimeSnapshot): void {
  writeFileSync(file, JSON.stringify(snapshot), { encoding: "utf8", mode: 0o600 });
}

export function readSafetySnapshot(file: string): ProtectedRuntimeSnapshot {
  return JSON.parse(readFileSync(file, "utf8")) as ProtectedRuntimeSnapshot;
}

function listenerPidsOnPort(port: number): number[] {
  try {
    const output = execFileSync("lsof", ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-t"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    return output.split(/\s+/).filter(Boolean).map(Number).filter(Number.isInteger).sort((a, b) => a - b);
  } catch {
    return [];
  }
}

function discoverDatabasePath(listenerPids: number[]): string | null {
  for (const pid of listenerPids) {
    const cwd = processCwd(pid);
    if (!cwd) continue;
    const candidate = path.join(cwd, "sba-agentic.db");
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

function processCwd(pid: number): string | null {
  try {
    if (process.platform === "linux") return readlinkSync(`/proc/${pid}/cwd`);
    const output = execFileSync("lsof", ["-a", "-p", String(pid), "-d", "cwd", "-Fn"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    return output.split("\n").find((line) => line.startsWith("n"))?.slice(1) || null;
  } catch {
    return null;
  }
}

function databaseIdentity(databasePath: string): string {
  if (!existsSync(databasePath)) return "missing";
  const stat = statSync(databasePath);
  return `${stat.dev}:${stat.ino}:${stat.mode}`;
}

function syntheticEventRows(databasePath: string): number | null {
  // The live service checkpoints its WAL while we read; a single attempt can
  // catch a transient SQLITE_BUSY and turn one unlucky moment into a null.
  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt > 0) execFileSync("sleep", ["0.3"]);
    try {
      const output = execFileSync("sqlite3", ["-readonly", databasePath, `
        SELECT count(*)
        FROM agent_events
        WHERE client_session_id IN (
          'black-box-e2e-codex-ui-rewrite',
          'black-box-e2e-codex-frontend-build',
          'black-box-e2e-claude-design-prompt',
          'black-box-e2e-codex-release-worktree',
          'black-box-saga-e2e-completion-session'
        );
      `], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
      });
      const count = Number(output.trim());
      if (Number.isInteger(count)) return count;
    } catch {
      // fall through to the next attempt
    }
  }
  return null;
}
