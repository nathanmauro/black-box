import { describe, expect, it, vi } from "vitest";
import type { AgentSession, ProjectSummary } from "./api";
import {
  clearRememberedProjectKey,
  projectMatchesSession,
  projectSearchText,
  projectShortName,
  rankProjects,
  readRememberedProjectKey,
  rememberProjectKey,
} from "./projects";

const projects: ProjectSummary[] = [
  {
    projectKey: "sba-key",
    canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
    label: "~/Developer/proj/sba-agentic",
    sessionCount: 4,
    eventCount: 120,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T20:00:00Z",
  },
  {
    projectKey: "cockpit-key",
    canonicalKey: "/Users/nathan/Developer/proj/cockpit",
    label: "~/Developer/proj/cockpit",
    sessionCount: 2,
    eventCount: 40,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T21:00:00Z",
  },
];

describe("project helpers", () => {
  it("derives short names and searchable text", () => {
    expect(projectShortName(projects[0])).toBe("sba-agentic");
    expect(projectSearchText(projects[0])).toContain("sba-agentic");
    expect(projectSearchText(projects[0])).toContain("/users/nathan/developer/proj/sba-agentic");
  });

  it("ranks recent fuzzy matches by short name and path", () => {
    expect(rankProjects(projects, "sba").map((p) => p.projectKey)).toEqual(["sba-key"]);
    expect(rankProjects(projects, "proj").map((p) => p.projectKey)).toEqual(["cockpit-key", "sba-key"]);
  });

  it("matches sessions by canonical project path", () => {
    const session = { cwd: "/Users/nathan/Developer/proj/sba-agentic/" } as AgentSession;
    expect(projectMatchesSession(projects[0], session)).toBe(true);
    expect(projectMatchesSession(projects[1], session)).toBe(false);
  });

  it("remembers and clears the last project key", () => {
    vi.stubGlobal("localStorage", window.localStorage);
    rememberProjectKey("sba-key");
    expect(readRememberedProjectKey()).toBe("sba-key");
    clearRememberedProjectKey();
    expect(readRememberedProjectKey()).toBeNull();
  });
});
