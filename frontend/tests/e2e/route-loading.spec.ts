import { gzipSync } from "node:zlib";
import { expect, test } from "@playwright/test";

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((finish) => {
    resolve = finish;
  });

  return { promise, resolve };
}

for (const path of ["/", "/companion"]) {
  test(`initial ${path} loads only its route JavaScript`, async ({ page }) => {
    const scripts: Array<Promise<{ file: string; bytes: number; gzipBytes: number }>> = [];

    page.on("response", (response) => {
      if (response.request().resourceType() !== "script") {
        return;
      }

      scripts.push(
        response.body().then((body) => ({
          file: new URL(response.url()).pathname,
          bytes: body.length,
          gzipBytes: gzipSync(body).length,
        })),
      );
    });
    await page.goto(path);

    if (path === "/") {
      await expect(page.getByRole("heading", { name: "Activity", exact: true })).toBeVisible();
      await expect(page.locator(".stream-row").first()).toBeVisible();
    } else {
      await expect(page.getByRole("button", { name: /Black Box companion/ })).toBeVisible();
      await expect(page.getByRole("banner", { name: "Black Box utility bar" })).toHaveCount(0);
    }

    const loaded = await Promise.all(scripts);
    const bytes = loaded.reduce((sum, script) => sum + script.bytes, 0);
    const gzipBytes = loaded.reduce((sum, script) => sum + script.gzipBytes, 0);

    console.log(JSON.stringify({ path, bytes, gzipBytes, scripts: loaded }, null, 2));
    // The eager baseline loaded 341,154 bytes on either route. Count every requested chunk,
    // including statically imported shared code, so a smaller entry alone cannot pass.
    expect(bytes).toBeLessThan(path === "/" ? 250_000 : 110_000);
    expect(loaded.some((script) => /(?:Projects|Recall|Ideas|Graph)Page-/.test(script.file))).toBe(
      false,
    );

    if (path === "/companion") {
      expect(loaded.some((script) => /(?:Activity|Sessions)Page-/.test(script.file))).toBe(false);
    }
  });
}

test("route loading keeps the shell usable and preserves the page's API loading state", async ({
  page,
}) => {
  const chunk = deferred();
  const api = deferred();

  await page.route("**/assets/ProjectsPage-*.js", async (route) => {
    await chunk.promise;
    await route.continue();
  });
  await page.route("**/api/projects", async (route) => {
    await api.promise;
    await route.continue();
  });

  try {
    await page.goto("/projects", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("status")).toHaveText("Loading view…");
    await expect(page.getByRole("banner", { name: "Black Box utility bar" })).toBeVisible();
    await page.getByRole("button", { name: "Open command palette" }).click();
    await expect(page.getByPlaceholder("Jump to session or filter Stream...")).toBeVisible();
    await page.keyboard.press("Escape");

    chunk.resolve();
    await expect(page.getByRole("heading", { name: "Projects", exact: true })).toBeVisible();
    await expect(page.getByText("Loading project catalog", { exact: true })).toBeVisible();
    await expect(page.getByText("Loading view…", { exact: true })).toHaveCount(0);
    api.resolve();
    await expect(page.getByRole("navigation", { name: "Project catalog" })).toBeVisible();
  } finally {
    chunk.resolve();
    api.resolve();
    await page.unrouteAll({ behavior: "wait" });
  }
});

test("a failed route chunk supports other navigation and reloads the same deep link", async ({
  page,
}) => {
  const errors: string[] = [];

  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/assets/ProjectsPage-*.js", (route) => route.abort("failed"));
  await page.goto("/projects?focus=kept-on-reload");
  await expect(page.getByRole("alert")).toHaveText(/This view could not be loaded/);
  await page.getByRole("link", { name: "Ideas", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Ideas", exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("alert")).toBeVisible();

  await page.unroute("**/assets/ProjectsPage-*.js");
  await page.getByRole("button", { name: "Reload view" }).click();
  await expect(page.getByRole("heading", { name: "Projects", exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/projects\?focus=kept-on-reload$/);
  expect(errors).toEqual([]);
});

test("navigation loads page chunks on demand and reuses them on return", async ({ page }) => {
  const requests: string[] = [];

  page.on("request", (request) => {
    if (request.resourceType() === "script") {
      requests.push(new URL(request.url()).pathname);
    }
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Activity", exact: true })).toBeVisible();

  for (const [label, heading] of [
    ["Projects", "Projects"],
    ["Recall", "Pick up where you left off"],
    ["Ideas", "Ideas"],
    ["Projects", "Projects"],
    ["Stream", "Activity"],
  ]) {
    await page.getByRole("link", { name: label, exact: true }).click();
    await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible();
  }

  for (const name of ["Activity", "Projects", "Recall", "Ideas"]) {
    expect(requests.filter((file) => file.includes(`/${name}Page-`))).toHaveLength(1);
  }

  expect(requests.some((file) => /(?:Companion|Graph)Page-/.test(file))).toBe(false);
});
