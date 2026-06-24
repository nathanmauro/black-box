import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { getDashboardStats } from "../../lib/api";
import StatsPage from "../StatsPage";

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getDashboardStats: vi.fn(async () => ({
      totalSessions: 2,
      totalEvents: 4,
      eventsBySource: [
        { name: "codex", count: 3 },
        { name: "claude", count: 1 },
      ],
      eventsByKind: [
        { name: "Decision", count: 2 },
        { name: "Handoff", count: 1 },
      ],
      sessionsBySource: [
        { name: "codex", count: 1 },
        { name: "claude", count: 1 },
      ],
      recentActivity: [
        { day: "2026-06-16", count: 1 },
        { day: "2026-06-17", count: 3 },
      ],
    })),
  };
});

describe("StatsPage", () => {
  it("loads dashboard stats and renders totals plus breakdowns", async () => {
    render(() => <StatsPage />);

    expect(getDashboardStats).toHaveBeenCalled();
    expect(await screen.findByText("2 sessions")).toBeInTheDocument();
    expect(screen.getByText("4 events")).toBeInTheDocument();
    expect(screen.getAllByText("codex").length).toBeGreaterThan(0);
    expect(screen.getByText("Decision")).toBeInTheDocument();
    expect(screen.getByText("2026-06-17")).toBeInTheDocument();
  });
});
