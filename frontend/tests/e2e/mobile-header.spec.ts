import { expect, test } from "@playwright/test";

for (const width of [320, 390, 768, 1440]) {
  test(`utility navigation and filters remain separate and keyboard reachable at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/recall");
    const header = page.getByRole("banner", { name: "Black Box utility bar" });
    await expect(header.getByRole("button", { name: "My turns", exact: true })).toBeVisible();
    const geometry = await header.evaluate((element) => {
      const controls = Array.from(element.querySelectorAll("a, button"))
        .filter((node) => {
          const rect = node.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0;
        })
        .map((node) => {
          const rect = node.getBoundingClientRect();
          return {
            name: node.getAttribute("aria-label") || node.textContent?.trim() || node.tagName,
            x: rect.x,
            y: rect.y,
            right: rect.right,
            bottom: rect.bottom,
          };
        });
      const overlaps: string[] = [];
      for (let left = 0; left < controls.length; left += 1) {
        for (let right = left + 1; right < controls.length; right += 1) {
          const a = controls[left];
          const b = controls[right];
          if (
            Math.min(a.right, b.right) - Math.max(a.x, b.x) > 1 &&
            Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y) > 1
          )
            overlaps.push(`${a.name} overlaps ${b.name}`);
        }
      }
      return { controls, overlaps, viewport: window.innerWidth };
    });
    await testInfo.attach(`header-geometry-${width}`, {
      body: JSON.stringify(geometry, null, 2),
      contentType: "application/json",
    });
    await page.screenshot({ path: testInfo.outputPath(`header-${width}.png`), fullPage: true });
    expect(geometry.overlaps).toEqual([]);
    for (const control of geometry.controls) {
      expect(control.x, `${control.name} starts inside viewport`).toBeGreaterThanOrEqual(0);
      expect(control.right, `${control.name} ends inside viewport`).toBeLessThanOrEqual(width);
    }

    // Tab order crosses every primary destination and the filter controls without hidden actions.
    const brand = header.getByRole("link", { name: "Black Box overview" });
    await brand.focus();
    for (const name of ["Stream", "Browse", "Projects", "Recall", "Ideas"]) {
      await page.keyboard.press("Tab");
      await expect(header.getByRole("link", { name, exact: true })).toBeFocused();
    }
    await page.keyboard.press("Tab");
    const sources = header.getByRole("button", { name: "Filter sources" });
    await expect(sources).toBeFocused();
    await page.keyboard.press("Enter");
    const sourcePanel = header.getByRole("group", { name: "Filter by source" });
    await expect(sourcePanel).toBeVisible();
    const panel = await page.locator("#source-filter-panel").boundingBox();
    expect(panel!.x).toBeGreaterThanOrEqual(0);
    expect(panel!.x + panel!.width).toBeLessThanOrEqual(width);
    await page.keyboard.press("Enter");
    await expect(sourcePanel).toBeHidden();
    await page.keyboard.press("Tab");
    const human = header.getByRole("button", { name: "My turns", exact: true });
    await expect(human).toBeFocused();
    await page.keyboard.press("Space");
    await expect(human).toHaveAttribute("aria-pressed", "true");
    await page.keyboard.press("Space");
    await expect(human).toHaveAttribute("aria-pressed", "false");
    await page.keyboard.press("Tab");
    await expect(header.getByRole("button", { name: "Open command palette" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("dialog", { name: "Command palette" })).toBeVisible();
    await page.keyboard.press("Escape");
    await header.getByRole("link", { name: "Projects", exact: true }).focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/projects$/);
    await header.getByRole("link", { name: "Recall", exact: true }).focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/recall$/);
  });
}
