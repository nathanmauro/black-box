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
// Inherited Spring/JVM properties can override the fixture datasource. Retain only runtime/OS
// settings, and start in the fresh fixture directory to exclude the checkout's external config.
const runtimeEnvironment = Object.fromEntries(
  ["PATH", "JAVA_HOME", "HOME", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL"].flatMap((key) =>
    process.env[key] === undefined ? [] : [[key, process.env[key]]],
  ),
);
const server = spawn("java", ["-jar", path.join(root, "target/sba-agentic-0.2.0.jar")], {
  cwd: fixture,
  stdio: ["ignore", serverLog.fd, serverLog.fd],
  env: {
    ...runtimeEnvironment,
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
  const originalDecision = {
    source: "manual",
    clientSessionId: "authenticated-browser-fixture",
    repo: "/repos/auth-fixture",
    decision: "Original browser authentication fixture",
    rationale: "Recorded evidence for the browser replacement journey.",
  };
  const seeded = await fetch(`${base}/api/decisions`, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiToken}`, "Content-Type": "application/json" },
    body: JSON.stringify(originalDecision),
  });
  assert(seeded.ok, "fixture decision is captured with the generated agent credential");
  const original = await seeded.json();
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.goto(`${base}/recall`);
  assert.equal(new URL(page.url()).pathname, "/login");
  await page.getByLabel("Username").fill("blackbox");
  await page.getByLabel("Password").fill(password);
  await Promise.all([
    page.waitForURL(`${base}/`),
    page.getByRole("button", { name: "Sign in" }).click(),
  ]);
  assert.equal(await page.evaluate(async () => (await fetch("/api/status")).status), 200);
  assert.equal(
    await page.evaluate(async (data) => {
      const response = await fetch("/api/decisions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
      });
      return response.status;
    }, originalDecision),
    403,
    "browser mutation without CSRF is rejected",
  );
  const recall = new URL(`${base}/recall`);
  recall.search = new URLSearchParams({ project: originalDecision.repo, run: "1" }).toString();
  await page.goto(recall.toString());
  await page
    .getByRole("article", { name: originalDecision.decision })
    .getByRole("button", { name: "Replace decision" })
    .click();
  const replacementTitle = "Verified browser authentication replacement";
  await page.getByLabel("New decision", { exact: true }).fill(replacementTitle);
  await page
    .getByLabel("Why this replaces the earlier decision")
    .fill("Prove authenticated frontend mutations use current CSRF credentials.");
  const decisionResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/decisions") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Record replacement", exact: true }).click();
  const response = await decisionResponse;
  assert(response.ok(), `frontend mutation succeeds: ${response.status()}`);
  assert(response.request().headers()["x-xsrf-token"], "packaged frontend sends CSRF token");
  const replacement = await response.json();
  assert.notEqual(replacement.eventId, original.eventId);
  await page.getByRole("article", { name: replacementTitle }).waitFor();
  assert.equal(await page.getByRole("article", { name: originalDecision.decision }).count(), 0);
  const evidence = await page.evaluate(
    async ({ eventId, repo }) => {
      const old = await (await fetch(`/api/events/${eventId}`)).json();
      const history = await (
        await fetch(
          `/api/recall?${new URLSearchParams({ project: repo, includeSuperseded: "true" })}`,
        )
      ).json();
      return { old, history };
    },
    { eventId: original.eventId, repo: originalDecision.repo },
  );
  assert.equal(evidence.old.metadata.decision, originalDecision.decision);
  assert.equal(
    evidence.history.items.length,
    2,
    "only the authorized original and replacement exist",
  );
  assert.equal(
    evidence.history.items.find((item) => item.eventId === original.eventId).supersededByEventId,
    replacement.eventId,
  );
  await page.screenshot({ path: path.join(fixture, "authenticated-recall.png"), fullPage: true });
  await page.goto(`${base}/logout`);
  await Promise.all([
    page.waitForURL((url) => url.pathname === "/login"),
    page.getByRole("button", { name: "Log Out" }).click(),
  ]);
  assert.equal(await page.evaluate(async () => (await fetch("/api/status")).status), 401);
  console.log(
    JSON.stringify({
      passed: true,
      flow: "login -> reject missing CSRF -> actual UI decision replacement -> preserved history -> logout -> anonymous denial",
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
