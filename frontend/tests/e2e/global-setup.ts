import type { FullConfig } from "@playwright/test";
import { seedBlackBoxE2e } from "../../src/e2e/seedData";
import {
  assertIsolatedDatabase,
  captureProtectedRuntime,
  safetySnapshotPath,
  writeSafetySnapshot,
} from "./runtime-safety";

export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]?.use.baseURL || process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:8799";
  const dbPath = String(config.metadata.blackBoxE2eDbPath || "");
  const tempDir = String(config.metadata.blackBoxE2eTempDir || "");
  assertIsolatedDatabase(dbPath, tempDir);

  const protectedRuntime = captureProtectedRuntime();
  writeSafetySnapshot(safetySnapshotPath(tempDir), protectedRuntime);
  console.log(`[black-box-saga-e2e] protected port 8766 listener PIDs before: ${formatPids(protectedRuntime.listenerPids)}`);
  console.log(`[black-box-saga-e2e] protected production DB before: ${protectedRuntime.databasePath || "not discovered"}`);
  await seedBlackBoxE2e(String(baseURL));
}

function formatPids(pids: number[]): string {
  return pids.length ? pids.join(",") : "none";
}
