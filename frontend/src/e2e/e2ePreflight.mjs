#!/usr/bin/env node

import {
  lstatSync,
  mkdirSync,
  openSync,
  closeSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DIRECTORY_PREFIX = "black-box-saga-e2e-";
const DATABASE_NAME = "black-box-saga-e2e.db";
const OWNERSHIP_FILE = ".black-box-saga-e2e-owned";

export function assertSafeE2ePathShape(tempDir, dbPath) {
  if (!tempDir || !dbPath) {
    throw new Error("The E2E database and temp directory must be explicit");
  }

  const systemTemp = realpathSync(os.tmpdir());
  const absoluteTemp = path.resolve(tempDir);
  const realParent = realpathSync(path.dirname(absoluteTemp));
  if (realParent !== systemTemp) {
    throw new Error(`Refusing E2E temp directory outside the real system temp root: ${tempDir}`);
  }
  if (!path.basename(absoluteTemp).startsWith(DIRECTORY_PREFIX)) {
    throw new Error(`Refusing unrecognized E2E temp directory: ${tempDir}`);
  }

  const absoluteDb = path.resolve(dbPath);
  if (path.dirname(absoluteDb) !== absoluteTemp || path.basename(absoluteDb) !== DATABASE_NAME) {
    throw new Error(`Refusing non-isolated E2E database path: ${dbPath}`);
  }
}

export function assertDatabaseArtifactsAbsent(tempDir, dbPath) {
  for (const candidate of [dbPath, `${dbPath}-wal`, `${dbPath}-shm`]) {
    try {
      const stat = lstatSync(candidate);
      const kind = stat.isSymbolicLink() ? "symlink" : "entry";
      throw new Error(`Refusing pre-existing SQLite ${kind}: ${candidate}`);
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
  }

  const resolvedTemp = realpathSync(tempDir);
  const resolvedDatabase = path.join(realpathSync(path.dirname(dbPath)), path.basename(dbPath));
  if (path.dirname(resolvedDatabase) !== resolvedTemp || path.basename(resolvedDatabase) !== DATABASE_NAME) {
    throw new Error(`Refusing database target outside the owned E2E directory: ${resolvedDatabase}`);
  }
}

export function preflightE2eStorage(tempDir, dbPath, runToken) {
  assertSafeE2ePathShape(tempDir, dbPath);
  if (!runToken) throw new Error("The E2E run ownership token must be explicit");

  let created = false;
  try {
    // recursive:false is intentional: EEXIST and every mkdir failure must stop the server launch.
    mkdirSync(tempDir, { mode: 0o700, recursive: false });
    created = true;

    const tempStat = lstatSync(tempDir);
    if (!tempStat.isDirectory() || tempStat.isSymbolicLink()) {
      throw new Error(`Refusing non-directory E2E temp path: ${tempDir}`);
    }

    const systemTemp = realpathSync(os.tmpdir());
    const resolvedTemp = realpathSync(tempDir);
    if (path.dirname(resolvedTemp) !== systemTemp
      || !path.basename(resolvedTemp).startsWith(DIRECTORY_PREFIX)) {
      throw new Error(`Refusing resolved E2E directory outside the real temp root: ${resolvedTemp}`);
    }

    assertDatabaseArtifactsAbsent(tempDir, dbPath);

    const marker = path.join(tempDir, OWNERSHIP_FILE);
    const markerFd = openSync(marker, "wx", 0o600);
    try {
      writeFileSync(markerFd, `${runToken}\n`, "utf8");
    } finally {
      closeSync(markerFd);
    }
  } catch (error) {
    if (created) rmSync(tempDir, { recursive: true, force: true });
    throw error;
  }
}

export function cleanupOwnedE2eStorage(tempDir, dbPath, runToken) {
  assertSafeE2ePathShape(tempDir, dbPath);
  const marker = path.join(tempDir, OWNERSHIP_FILE);
  let recordedToken;
  try {
    recordedToken = readFileSync(marker, "utf8").trim();
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
  if (!runToken || recordedToken !== runToken) {
    throw new Error(`Refusing cleanup of E2E directory without matching ownership: ${tempDir}`);
  }
  rmSync(tempDir, { recursive: true, force: false });
  return true;
}

function main() {
  const [operation = "prepare"] = process.argv.slice(2);
  const tempDir = process.env.SBA_E2E_TEMP_DIR || "";
  const dbPath = process.env.SBA_E2E_DB_PATH || "";
  const runToken = process.env.SBA_E2E_RUN_TOKEN || "";
  if (operation === "prepare") {
    preflightE2eStorage(tempDir, dbPath, runToken);
    console.log(`BLACK_BOX_E2E_STORAGE_READY=${dbPath}`);
    return;
  }
  if (operation === "cleanup") {
    const cleaned = cleanupOwnedE2eStorage(tempDir, dbPath, runToken);
    if (cleaned) console.log(`BLACK_BOX_E2E_DB_CLEANED=${dbPath}`);
    return;
  }
  throw new Error(`Unknown E2E storage operation: ${operation}`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(`[black-box-saga-e2e] storage preflight failed: ${error?.message || error}`);
    process.exitCode = 1;
  }
}
