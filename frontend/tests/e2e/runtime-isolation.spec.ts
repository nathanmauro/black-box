import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertIsolatedDatabase } from "./runtime-safety";

test("a captured event belongs to the fixture database and is reachable through Browse", async ({
  page,
  request,
}) => {
  const dbPath = String(test.info().config.metadata.blackBoxE2eDbPath || "");
  const tempDir = String(test.info().config.metadata.blackBoxE2eTempDir || "");
  assertIsolatedDatabase(dbPath, tempDir);
  const marker = `Runtime isolation capture ${randomUUID()}`;
  const captured = await request.post("/api/events", {
    data: {
      source: "codex",
      clientSessionId: `black-box-e2e-runtime-${randomUUID()}`,
      eventType: "Observation",
      role: "assistant",
      text: marker,
      cwd: "/tmp/black-box-e2e",
      metadata: { title: marker },
    },
  });
  expect(captured.ok()).toBeTruthy();
  const event = (await captured.json()) as { eventId: string; sessionId: string };
  // Query the actual intended file read-only, not a configured URL or a healthy HTTP server.
  const persisted = execFileSync(
    "python3",
    [
      "-c",
      `import pathlib, sqlite3, sys
connection = sqlite3.connect(pathlib.Path(sys.argv[1]).resolve().as_uri() + '?mode=ro', uri=True)
row = connection.execute('SELECT text FROM agent_events WHERE id = ?', (sys.argv[2],)).fetchone()
assert row is not None, 'captured event missing from intended fixture database'
print(row[0], end='')`,
      dbPath,
      event.eventId,
    ],
    { encoding: "utf8" },
  );
  expect(persisted).toBe(marker);
  await page.goto(`/?q=${encodeURIComponent(marker)}`);
  const row = page.locator(".stream-row").filter({ hasText: marker });
  await expect(row).toBeVisible();
  await row.click();
  await page.getByRole("link", { name: "Open at this event" }).click();
  await expect(page).toHaveURL(
    (url) =>
      url.searchParams.get("view") === "browse" &&
      url.searchParams.get("session") === event.sessionId &&
      url.searchParams.get("event") === event.eventId,
  );
  await expect(page.locator(".event-flow-row--target")).toContainText(marker);
});
