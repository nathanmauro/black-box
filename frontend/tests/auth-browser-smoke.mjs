// Run after packaging: node frontend/tests/auth-browser-smoke.mjs
// Owns an isolated temporary database and child server; never uses the local live service.
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtemp, open } from "node:fs/promises";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const root = fileURLToPath(new URL("../../", import.meta.url));
const fixture = await mkdtemp(path.join(tmpdir(), "blackbox-auth-browser-"));
const portProbe = createServer();
portProbe.listen(0, "127.0.0.1");
await once(portProbe, "listening");
const port = portProbe.address().port;
await new Promise((resolve) => portProbe.close(resolve));
const password = randomBytes(48).toString("base64url");
const apiToken = randomBytes(48).toString("base64url");
const serverLog = await open(path.join(fixture, "server.log"), "w");
const server = spawn("java", ["-jar", "target/sba-agentic-0.2.0.jar"], {
  cwd: root,
  stdio: ["ignore", serverLog.fd, serverLog.fd],
  env: {
    ...process.env,
    SBA_PORT: String(port),
    SBA_BIND_ADDRESS: "127.0.0.1",
    SBA_DATASOURCE_URL: `jdbc:sqlite:${path.join(fixture, "fixture.db")}`,
    SBA_AUTH_ENABLED: "true",
    SBA_AUTH_SECURE_COOKIES: "false",
    SBA_AUTH_USERNAME: "blackbox",
    SBA_AUTH_PASSWORD: password,
    SBA_AUTH_API_TOKEN: apiToken,
    SBA_LOCAL_AI_ENABLED: "false",
    SBA_ELASTICSEARCH_ENABLED: "false",
    SBA_SUMMARY_BACKEND: "local",
    SBA_MEMORY_EMBEDDING_ENABLED: "false",
    SBA_EDITOR_ENABLED: "false",
  },
});
const base = `http://127.0.0.1:${port}`;
let browser;
try {
  let ready = false;
  for (let attempt = 0; attempt < 150; attempt++) {
    if (server.exitCode !== null) throw new Error("Fixture server exited; inspect its server.log");
    try {
      if ((await fetch(`${base}/actuator/health`)).ok) {
        ready = true;
        break;
      }
    } catch {
      /* not listening yet */
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  assert(ready, "fixture server becomes healthy");
  assert.equal((await fetch(`${base}/api/status`)).status, 401);
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.goto(`${base}/board`);
  assert.equal(new URL(page.url()).pathname, "/login");
  await page.getByLabel("Username").fill("blackbox");
  await page.getByLabel("Password").fill(password);
  await Promise.all([
    page.waitForURL(`${base}/`),
    page.getByRole("button", { name: "Sign in" }).click(),
  ]);
  assert.equal(await page.evaluate(async () => (await fetch("/api/status")).status), 200);
  await page.goto(`${base}/board`);
  await page.getByRole("button", { name: "New story" }).click();
  await page.getByLabel("Title", { exact: true }).fill("Browser authentication fixture");
  await page.getByLabel("Repo path", { exact: true }).fill("/repos/auth-fixture");
  await page
    .getByLabel("Goal", { exact: true })
    .fill("Prove authenticated frontend mutations use current CSRF credentials.");
  const specResponse = page.waitForResponse(
    (response) => response.url().endsWith("/api/specs") && response.request().method() === "POST",
  );
  const taskResponse = page.waitForResponse(
    (response) => response.url().endsWith("/api/tasks") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Create story", exact: true }).click();
  for (const response of await Promise.all([specResponse, taskResponse])) {
    assert(response.ok(), `frontend mutation succeeds: ${response.status()}`);
    assert(response.request().headers()["x-xsrf-token"], "packaged frontend sends CSRF token");
  }
  await page.screenshot({ path: path.join(fixture, "authenticated-board.png"), fullPage: true });
  await page.goto(`${base}/logout`);
  await Promise.all([
    page.waitForURL((url) => url.pathname === "/login"),
    page.getByRole("button", { name: "Log Out" }).click(),
  ]);
  assert.equal(await page.evaluate(async () => (await fetch("/api/status")).status), 401);
  console.log(
    JSON.stringify({
      passed: true,
      flow: "login -> read -> actual UI create spec/task -> logout -> anonymous denial",
      fixture,
    }),
  );
} finally {
  await browser?.close();
  server.kill("SIGTERM");
  const timeout = setTimeout(() => server.kill("SIGKILL"), 5000);
  await once(server, "exit");
  clearTimeout(timeout);
  await serverLog.close();
}
