// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import { randomUUID } from "node:crypto";
// prettier-ignore
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import { spawn, spawnSync } from "node:child_process";
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import os from "node:os";
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import path from "node:path";
// @ts-expect-error Node built-in types are intentionally excluded from the browser tsconfig.
import process from "node:process";
import { afterEach, describe, expect, it, vi } from "vitest";
// @ts-expect-error Plain ESM is shared with the executable Playwright config.
import { E2E_SERVER_COMMAND } from "./e2eServer.mjs";

const created: string[] = [];
function fixture(javaExit = 0, buildExit = 0, waitForSignal = false) {
  const root = realpathSync(mkdtempSync(path.join(os.tmpdir(), "black-box-e2e-runtime-test-")));
  const tempDir = path.join(realpathSync(os.tmpdir()), `black-box-saga-e2e-${randomUUID()}`);
  created.push(root, tempDir);
  const repo = path.join(root, "repo with spaces");
  const bin = path.join(root, "bin");
  const record = path.join(root, "java.json");
  mkdirSync(path.join(repo, "frontend/src/e2e"), { recursive: true });
  mkdirSync(bin);
  copyFileSync(
    path.resolve("src/e2e/e2ePreflight.mjs"),
    path.join(repo, "frontend/src/e2e/e2ePreflight.mjs"),
  );
  writeFileSync(
    path.join(repo, "application.yml"),
    "spring.datasource.url: jdbc:sqlite:decoy.db\n",
  );
  writeFileSync(path.join(bin, "mvn"), `#!/bin/sh\nexit ${buildExit}\n`, { mode: 0o700 });
  writeFileSync(
    path.join(bin, "java"),
    `#!${process.execPath}
const fs = require('node:fs');
const record = ${JSON.stringify(record)};
fs.writeFileSync(record, JSON.stringify({cwd:process.cwd(),env:process.env,args:process.argv.slice(2),pid:process.pid}));
${waitForSignal ? `process.on('SIGTERM',()=>{fs.writeFileSync(record+'.stopped','stopped');process.exit(0)});setInterval(()=>{},1000);` : `process.exit(${javaExit});`}
`,
    { mode: 0o700 },
  );
  const dbPath = path.join(tempDir, "black-box-saga-e2e.db");
  const poison = {
    SPRING_APPLICATION_JSON: '{"spring":{"datasource":{"url":"jdbc:sqlite:decoy.db"}}}',
    SPRING_CONFIG_LOCATION: "file:application.yml",
    SPRING_CONFIG_ADDITIONAL_LOCATION: "file:decoy.yml",
    SPRING_CONFIG_IMPORT: "file:decoy.yml",
    SPRING_PROFILES_ACTIVE: "postgres",
    SPRING_DATASOURCE_URL: "jdbc:sqlite:decoy.db",
    JAVA_TOOL_OPTIONS: "-Dspring.datasource.url=jdbc:sqlite:decoy-jvm.db",
    JDK_JAVA_OPTIONS: "-Dserver.port=8766",
    _JAVA_OPTIONS: "-Dsba.judge.enabled=true",
    CLASSPATH: "decoy-classes",
    SBA_SUMMARY_BACKEND: "external",
    SBA_SUMMARY_EXTERNAL_COMMAND: "must-not-run",
    SBA_JUDGE_ENABLED: "true",
    SBA_MEMORY_EMBEDDING_ENABLED: "true",
    SBA_LOCAL_AI_ENABLED: "true",
    SBA_EXPORT_OBSIDIAN_DIR: path.join(root, "must-not-export"),
    SBA_RETIRE_WORKFLOW: "true",
    SBA_EDITOR_COMMAND: "must-not-run",
    SBA_DATASOURCE_URL: "jdbc:sqlite:decoy.db",
    OPENAI_API_KEY: "fixture-only-key",
  };
  const env = {
    ...process.env,
    ...poison,
    PATH: `${bin}${path.delimiter}${process.env.PATH}`,
    SBA_E2E_TEMP_DIR: tempDir,
    SBA_E2E_DB_PATH: dbPath,
    SBA_E2E_RUN_TOKEN: randomUUID(),
    SBA_PORT: "8799",
    SBA_BIND_ADDRESS: "127.0.0.1",
  };
  const run = () =>
    spawnSync("sh", ["-c", E2E_SERVER_COMMAND], { cwd: repo, env, encoding: "utf8" });
  return { root, repo, tempDir, dbPath, record, env, poison, run };
}

afterEach(() => {
  for (const target of created.splice(0)) rmSync(target, { recursive: true, force: true });
});

describe("actual E2E server launch boundary", () => {
  it("observes a clean Java environment, private cwd and explicit packaged config", () => {
    const f = fixture();
    const result = f.run();
    expect(result.status, result.stderr).toBe(0);
    const actual = JSON.parse(readFileSync(f.record, "utf8"));
    expect(actual.cwd).toBe(f.tempDir);
    expect(actual.args).toEqual([
      "-jar",
      path.join(f.repo, "target/sba-agentic-0.2.0.jar"),
      "--spring.config.location=classpath:/application.yml",
      "--spring.profiles.active=default",
    ]);
    for (const [name, value] of Object.entries(f.poison)) expect(actual.env[name]).not.toBe(value);
    expect(actual.env.SBA_DATASOURCE_URL).toBe(`jdbc:sqlite:${f.dbPath}`);
    expect(actual.env.SBA_E2E_RUN_TOKEN).toBe(f.env.SBA_E2E_RUN_TOKEN);
    expect(actual.env.HOME).toBe(f.tempDir);
    expect(actual.env.SBA_PORT).toBe("8799");
    expect(actual.env.SBA_BIND_ADDRESS).toBe("127.0.0.1");
    expect(actual.env.SBA_SUMMARY_BACKEND).toBe("local");
    for (const name of ["LOCAL_AI", "ELASTICSEARCH", "MEMORY_EMBEDDING", "ASK_EMBEDDING", "JUDGE"])
      expect(actual.env[`SBA_${name}_ENABLED`]).toBe("false");
    expect(actual.env.SBA_EDITOR_ENABLED).toBe("true");
    expect(actual.env.SBA_EDITOR_COMMAND).toBe(path.join(f.tempDir, "fake-editor"));
    expect(actual.env.SBA_EDITOR_ALLOWLIST).toBe(actual.env.SBA_EDITOR_COMMAND);
    expect(actual.env.SBA_EDITOR_TIMEOUT).toBe("2s");
    expect(actual.env.SBA_E2E_EDITOR_LOG).toBe(path.join(f.tempDir, "editor-argv.bin"));
    expect(actual.env.SBA_E2E_INJECTION_SENTINEL).toBe(path.join(f.tempDir, "injection-sentinel"));
    expect(existsSync(f.tempDir)).toBe(false);
    expect(existsSync(path.join(f.repo, "decoy.db"))).toBe(false);
  });
  it("propagates Java failure and removes owned storage", () => {
    const f = fixture(23);
    expect(f.run().status).toBe(23);
    expect(existsSync(f.record)).toBe(true);
    expect(existsSync(f.tempDir)).toBe(false);
  });
  it("cleans up build failure before launching Java", () => {
    const f = fixture(0, 42);
    expect(f.run().status).toBe(42);
    expect(existsSync(f.record)).toBe(false);
    expect(existsSync(f.tempDir)).toBe(false);
  });
  it("leaves pre-existing storage alone and never launches Java", () => {
    const f = fixture();
    mkdirSync(f.tempDir);
    writeFileSync(f.dbPath, "unowned fixture sentinel");
    expect(f.run().status).not.toBe(0);
    expect(existsSync(f.record)).toBe(false);
    expect(readFileSync(f.dbPath, "utf8")).toBe("unowned fixture sentinel");
  });
  it("refuses a runtime listener other than the guarded loopback fixture", () => {
    for (const override of [{ SBA_PORT: "8766" }, { SBA_BIND_ADDRESS: "0.0.0.0" }]) {
      const f = fixture();
      Object.assign(f.env, override);
      expect(f.run().status).not.toBe(0);
      expect(existsSync(f.record)).toBe(false);
      expect(existsSync(f.tempDir)).toBe(false);
    }
  });
  it("terminates Java and waits before removing owned storage", async () => {
    const f = fixture(0, 0, true);
    const child = spawn("sh", ["-c", E2E_SERVER_COMMAND], {
      cwd: f.repo,
      env: f.env,
      stdio: "ignore",
    });
    const finished = new Promise((resolve) => child.once("close", resolve));
    try {
      await vi.waitFor(() => expect(existsSync(f.record)).toBe(true));
      child.kill("SIGTERM");
      await finished;
      expect(readFileSync(`${f.record}.stopped`, "utf8")).toBe("stopped");
      expect(existsSync(f.tempDir)).toBe(false);
      const pid = JSON.parse(readFileSync(f.record, "utf8")).pid;
      expect(() => process.kill(pid, 0)).toThrow();
    } finally {
      child.kill("SIGTERM");
    }
  });
});
