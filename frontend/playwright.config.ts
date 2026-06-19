import { defineConfig, devices } from "@playwright/test";
import os from "node:os";
import path from "node:path";
import { assertSafeSeedBaseUrl } from "./src/e2e/seedData";

const baseURL = process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:8799";
assertSafeSeedBaseUrl(baseURL);

const appUrl = new URL(baseURL);
const port = appUrl.port || (appUrl.protocol === "https:" ? "443" : "80");
const host = appUrl.hostname === "localhost" ? "127.0.0.1" : appUrl.hostname;
const dbPath = process.env.SBA_E2E_DB_PATH || path.join(os.tmpdir(), `blackbox-playwright-${port}-${process.pid}.db`);

// Drives a packaged Black Box jar against an isolated SQLite database. The production launchd
// service stays on 8766 and is never reused for this gate.
export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  reporter: [["list"]],
  globalSetup: "./tests/e2e/global-setup.ts",
  webServer: {
    command:
      'sh -c \'rm -f "$SBA_E2E_DB_PATH" "$SBA_E2E_DB_PATH-shm" "$SBA_E2E_DB_PATH-wal"; mvn -q -Pfrontend -DskipTests package && exec java -jar target/sba-agentic-0.1.0.jar\'',
    cwd: "..",
    url: new URL("/api/status", baseURL).toString(),
    timeout: 180_000,
    reuseExistingServer: false,
    stdout: "pipe",
    stderr: "pipe",
    gracefulShutdown: { signal: "SIGTERM", timeout: 5_000 },
    env: {
      SBA_E2E_DB_PATH: dbPath,
      SBA_PORT: port,
      SBA_BIND_ADDRESS: host,
      SBA_DATASOURCE_URL: `jdbc:sqlite:${dbPath}`,
      SBA_ELASTICSEARCH_ENABLED: "false",
      SBA_LOCAL_AI_ENABLED: "false",
      SBA_ASK_EMBEDDING_ENABLED: "false",
      SBA_SUMMARY_BACKEND: "local",
    },
  },
  use: {
    baseURL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
