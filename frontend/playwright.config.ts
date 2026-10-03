import { defineConfig, devices } from "@playwright/test";
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";
import { assertSafeSeedBaseUrl } from "./src/e2e/seedData";
import { E2E_SERVER_COMMAND } from "./src/e2e/e2eServer.mjs";

const baseURL = process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:8799";
assertSafeSeedBaseUrl(baseURL);

const appUrl = new URL(baseURL);
const port = appUrl.port || (appUrl.protocol === "https:" ? "443" : "80");
const host = appUrl.hostname === "localhost" ? "127.0.0.1" : appUrl.hostname;
const tempDir =
  process.env.SBA_E2E_TEMP_DIR || path.join(os.tmpdir(), `black-box-saga-e2e-${randomUUID()}`);
const dbPath = process.env.SBA_E2E_DB_PATH || path.join(tempDir, "black-box-saga-e2e.db");
const runToken = process.env.SBA_E2E_RUN_TOKEN || randomUUID();
assertSafeE2ePaths(tempDir, dbPath);
// Playwright reloads its config in child processes. Export the first path so every process shares
// one run directory instead of creating unused siblings that cannot be cleaned by the web server.
process.env.SBA_E2E_TEMP_DIR = tempDir;
process.env.SBA_E2E_DB_PATH = dbPath;
process.env.SBA_E2E_RUN_TOKEN = runToken;

function assertSafeE2ePaths(candidateTempDir: string, candidateDbPath: string): void {
  const relativeTemp = path.relative(path.resolve(os.tmpdir()), path.resolve(candidateTempDir));
  if (!relativeTemp || relativeTemp.startsWith("..") || path.isAbsolute(relativeTemp)) {
    throw new Error(
      `Refusing E2E temp directory outside the system temp root: ${candidateTempDir}`,
    );
  }
  if (!path.basename(candidateTempDir).startsWith("black-box-saga-e2e-")) {
    throw new Error(`Refusing unrecognized E2E temp directory: ${candidateTempDir}`);
  }
  if (
    path.resolve(path.dirname(candidateDbPath)) !== path.resolve(candidateTempDir) ||
    path.basename(candidateDbPath) !== "black-box-saga-e2e.db"
  ) {
    throw new Error(`Refusing non-isolated E2E database path: ${candidateDbPath}`);
  }
}

// Drives a packaged Black Box jar against an isolated SQLite database. The production launchd
// service stays on 8766 and is never reused for this gate.
export default defineConfig({
  metadata: {
    blackBoxE2eDbPath: dbPath,
    blackBoxE2eTempDir: tempDir,
    blackBoxE2eRunToken: runToken,
  },
  testDir: "./tests/e2e",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  workers: 1,
  fullyParallel: false,
  reporter: [["list"]],
  globalSetup: "./tests/e2e/global-setup.ts",
  globalTeardown: "./tests/e2e/global-teardown.ts",
  webServer: {
    command: E2E_SERVER_COMMAND,
    cwd: "..",
    url: new URL("/api/status", baseURL).toString(),
    timeout: 180_000,
    reuseExistingServer: false,
    stdout: "pipe",
    stderr: "pipe",
    gracefulShutdown: { signal: "SIGTERM", timeout: 15_000 },
    env: {
      SBA_E2E_DB_PATH: dbPath,
      SBA_E2E_TEMP_DIR: tempDir,
      SBA_E2E_RUN_TOKEN: runToken,
      SBA_PORT: port,
      SBA_BIND_ADDRESS: host,
    },
  },
  use: {
    baseURL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
