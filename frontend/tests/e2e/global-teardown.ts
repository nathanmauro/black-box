import type { FullConfig } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import path from "node:path";
import { cleanupOwnedE2eStorage } from "../../src/e2e/e2ePreflight.mjs";
import { cleanupProjectFixture } from "./project-fixture";
import {
  assertProtectedRuntimeUnchanged,
  captureProtectedRuntime,
  readSafetySnapshot,
  safetySnapshotPath,
} from "./runtime-safety";

export default async function globalTeardown(config: FullConfig) {
  const tempDir = String(config.metadata.blackBoxE2eTempDir || "");
  const dbPath = String(config.metadata.blackBoxE2eDbPath || "");
  const runToken = String(config.metadata.blackBoxE2eRunToken || "");
  const snapshotFile = safetySnapshotPath(tempDir);
  try {
    if (!existsSync(snapshotFile)) {
      console.log("[black-box-saga-e2e] protected runtime comparison skipped because global setup did not finish");
      return;
    }
    const before = readSafetySnapshot(snapshotFile);
    const after = captureProtectedRuntime(before.databasePath);
    assertProtectedRuntimeUnchanged(before, after);
    console.log(`[black-box-saga-e2e] protected port 8766 listener PIDs unchanged: ${formatPids(after.listenerPids)}`);
    console.log(`[black-box-saga-e2e] protected production DB identity unchanged: ${after.databasePath || "not discovered"}`);
    console.log(`[black-box-saga-e2e] production synthetic-event row count unchanged: ${after.syntheticEventRows ?? "unavailable"}`);
  } finally {
    cleanupPrivateTmux(String(config.metadata.blackBoxE2eTmuxTmpDir || ""));
    const projectFixtureCleaned = cleanupProjectFixture(tempDir);
    console.log(projectFixtureCleaned
      ? "[black-box-saga-e2e] isolated project fixture removed"
      : "[black-box-saga-e2e] isolated project fixture was already removed");
    const cleaned = cleanupOwnedE2eStorage(tempDir, dbPath, runToken);
    console.log(cleaned
      ? `[black-box-saga-e2e] isolated temp database removed: ${tempDir}`
      : `[black-box-saga-e2e] isolated temp database was already removed: ${tempDir}`);
  }
}

function formatPids(pids: number[]): string {
  return pids.length ? pids.join(",") : "none";
}

function cleanupPrivateTmux(tmuxTmpDir: string): void {
  if (!path.basename(tmuxTmpDir).startsWith("bb-tmux-")) return;
  try {
    execFileSync("tmux", ["kill-server"], {
      stdio: "ignore",
      env: { ...process.env, TMUX_TMPDIR: tmuxTmpDir },
    });
    console.log("[black-box-saga-e2e] run-private tmux server killed");
  } catch {
    console.log("[black-box-saga-e2e] no run-private tmux server to kill");
  }
  rmSync(tmuxTmpDir, { recursive: true, force: true });
}
