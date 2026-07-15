import { describe, expect, it, vi } from "vitest";
import type { AgentSession, ProjectSummary } from "./api";
import {
  clearRememberedProjectKey,
  findProjectByIdentifier,
  primaryProjectScope,
  projectMatchesSession,
  projectScopes,
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
    scopes: [
      {
        projectKey: "sba-key",
        canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
        label: "~/Developer/proj/sba-agentic",
        primary: true,
      },
      {
        projectKey: "sba-worktree-key",
        canonicalKey: "/Users/nathan/.codex/worktrees/abc/sba-agentic",
        label: "~/.codex/worktrees/abc/sba-agentic",
        primary: false,
      },
    ],
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
    expect(rankProjects(projects, "worktrees/abc").map((p) => p.projectKey)).toEqual(["sba-key"]);
  });

  it("resolves grouped identities and matches sessions through variant scopes", () => {
    const session = { cwd: "/Users/nathan/Developer/proj/sba-agentic/" } as AgentSession;
    expect(projectMatchesSession(projects[0], session)).toBe(true);
    expect(projectMatchesSession(projects[1], session)).toBe(false);
    expect(projectMatchesSession(projects[0], { cwd: "/Users/nathan/.codex/worktrees/abc/sba-agentic" } as AgentSession)).toBe(true);
    expect(findProjectByIdentifier(projects, "sba-worktree-key")).toBe(projects[0]);
    expect(findProjectByIdentifier(projects, "/Users/nathan/.codex/worktrees/abc/sba-agentic")).toBe(projects[0]);
    expect(primaryProjectScope(projects[0]).projectKey).toBe("sba-key");
    expect(projectScopes(projects[0])).toHaveLength(2);
  });

  it("uses a friendly label for the no-project sentinel", () => {
    const noProject: ProjectSummary = {
      projectKey: "none",
      canonicalKey: "__no_project__",
      label: "__no_project__",
      sessionCount: 1,
      eventCount: 1,
      savedMeldCount: 0,
    };
    expect(projectShortName(noProject)).toBe("No project / system context");
  });

  it("remembers and clears the last project key", () => {
    vi.stubGlobal("localStorage", window.localStorage);
    rememberProjectKey("sba-key");
    expect(readRememberedProjectKey()).toBe("sba-key");
    clearRememberedProjectKey();
    expect(readRememberedProjectKey()).toBeNull();
  });
});
