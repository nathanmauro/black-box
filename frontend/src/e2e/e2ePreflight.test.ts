// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import { randomUUID } from "node:crypto";
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import { existsSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import os from "node:os";
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
// @ts-expect-error The executable preflight remains plain ESM so Playwright can run it with Node.
import { assertDatabaseArtifactsAbsent, cleanupOwnedE2eStorage, preflightE2eStorage } from "./e2ePreflight.mjs";

const createdPaths: string[] = [];

function isolatedPaths(suffix = randomUUID()) {
  const tempDir = path.join(os.tmpdir(), `black-box-saga-e2e-${suffix}`);
  createdPaths.push(tempDir);
  return { tempDir, dbPath: path.join(tempDir, "black-box-saga-e2e.db") };
}

afterEach(() => {
  for (const candidate of createdPaths.splice(0)) {
    rmSync(candidate, { recursive: true, force: true });
  }
});

describe("E2E storage ownership preflight", () => {
  it("exclusively creates and cleans a unique owned temp directory", () => {
    const { tempDir, dbPath } = isolatedPaths();
    const token = randomUUID();

    preflightE2eStorage(tempDir, dbPath, token);

    expect(readFileSync(path.join(tempDir, ".black-box-saga-e2e-owned"), "utf8").trim()).toBe(token);
    expect(cleanupOwnedE2eStorage(tempDir, dbPath, token)).toBe(true);
    expect(cleanupOwnedE2eStorage(tempDir, dbPath, token)).toBe(false);
  });

  it("fails closed without deleting a pre-existing matching directory", () => {
    const { tempDir, dbPath } = isolatedPaths();
    mkdirSync(tempDir, { mode: 0o700 });
    const sentinel = path.join(tempDir, "must-survive");
    writeFileSync(sentinel, "owned by someone else", "utf8");

    expect(() => preflightE2eStorage(tempDir, dbPath, randomUUID())).toThrow();
    expect(readFileSync(sentinel, "utf8")).toBe("owned by someone else");
  });

  it("refuses a database symlink and SQLite sidecar entries", () => {
    const { tempDir, dbPath } = isolatedPaths();
    mkdirSync(tempDir, { mode: 0o700 });
    symlinkSync("/dev/null", dbPath);

    expect(() => assertDatabaseArtifactsAbsent(tempDir, dbPath)).toThrow(/SQLite symlink/);

    rmSync(dbPath);
    writeFileSync(`${dbPath}-wal`, "not ours", "utf8");
    expect(() => assertDatabaseArtifactsAbsent(tempDir, dbPath)).toThrow(/pre-existing SQLite entry/);
  });

  it("propagates mkdir failure before creating any database artifact", () => {
    const tooLongName = `black-box-saga-e2e-${"x".repeat(300)}`;
    const tempDir = path.join(os.tmpdir(), tooLongName);
    const dbPath = path.join(tempDir, "black-box-saga-e2e.db");

    expect(() => preflightE2eStorage(tempDir, dbPath, randomUUID())).toThrow();
    expect(existsSync(dbPath)).toBe(false);
  });

  it("refuses an override outside the real system temp root", () => {
    const tempDir = path.join(os.homedir(), `black-box-saga-e2e-${randomUUID()}`);
    const dbPath = path.join(tempDir, "black-box-saga-e2e.db");

    expect(() => preflightE2eStorage(tempDir, dbPath, randomUUID())).toThrow(/real system temp root/);
    expect(existsSync(tempDir)).toBe(false);
  });

  it("refuses cleanup with a different ownership token", () => {
    const { tempDir, dbPath } = isolatedPaths();
    preflightE2eStorage(tempDir, dbPath, "owner-token");

    expect(() => cleanupOwnedE2eStorage(tempDir, dbPath, "other-token")).toThrow(/matching ownership/);
    expect(existsSync(tempDir)).toBe(true);
  });
});
