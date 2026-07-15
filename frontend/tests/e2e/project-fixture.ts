import { lstatSync, mkdirSync, readlinkSync, symlinkSync, unlinkSync } from "node:fs";
import path from "node:path";
import { E2E_PROJECT_CWD } from "../../src/e2e/seedData";

const FIXTURE_DIRECTORY = "project-fixture";

export function prepareProjectFixture(tempDir: string): void {
  const target = fixtureTarget(tempDir);
  mkdirSync(path.join(target, ".git"), { recursive: true, mode: 0o700 });
  try {
    symlinkSync(target, E2E_PROJECT_CWD, "dir");
  } catch (error) {
    throw new Error(
      `Refusing to replace pre-existing E2E project path ${E2E_PROJECT_CWD}: ${error instanceof Error ? error.message : error}`,
    );
  }
}

export function cleanupProjectFixture(tempDir: string): boolean {
  let stat;
  try {
    stat = lstatSync(E2E_PROJECT_CWD);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
    throw error;
  }
  if (!stat.isSymbolicLink()) {
    throw new Error(`Refusing to remove non-symlink E2E project path: ${E2E_PROJECT_CWD}`);
  }
  const linkedTarget = path.resolve(path.dirname(E2E_PROJECT_CWD), readlinkSync(E2E_PROJECT_CWD));
  const expectedTarget = path.resolve(fixtureTarget(tempDir));
  if (linkedTarget !== expectedTarget) {
    throw new Error(`Refusing to remove E2E project symlink owned by another run: ${E2E_PROJECT_CWD}`);
  }
  unlinkSync(E2E_PROJECT_CWD);
  return true;
}

function fixtureTarget(tempDir: string): string {
  return path.join(tempDir, FIXTURE_DIRECTORY);
}
