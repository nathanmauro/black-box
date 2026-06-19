import type { FullConfig } from "@playwright/test";
import { seedBlackBoxE2e } from "../../src/e2e/seedData";

export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]?.use.baseURL || process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:8799";
  await seedBlackBoxE2e(String(baseURL));
}
