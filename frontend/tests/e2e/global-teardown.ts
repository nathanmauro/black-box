import type { FullConfig } from "@playwright/test";
import { existsSync, rmSync } from "node:fs";
import {
  assertProtectedRuntimeUnchanged,
  captureProtectedRuntime,
  readSafetySnapshot,
  safetySnapshotPath,
} from "./runtime-safety";

export default async function globalTeardown(config: FullConfig) {
  const tempDir = String(config.metadata.blackBoxE2eTempDir || "");
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
    if (tempDir) rmSync(tempDir, { recursive: true, force: true });
    console.log(`[black-box-saga-e2e] isolated temp database removed: ${tempDir}`);
  }
}

function formatPids(pids: number[]): string {
  return pids.length ? pids.join(",") : "none";
}
