import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import MiniChip from "./MiniChip";

describe("MiniChip", () => {
  it("shows pulse and unseen count and expands on click", () => {
    const onExpand = vi.fn();
    render(() => <MiniChip pulse="live" unseen={3} onExpand={onExpand} />);
    const chip = screen.getByRole("button", { name: "Black Box companion: live, 3 unseen" });
    expect(chip).toHaveClass("companion-chip--live");
    expect(screen.getByText("3")).toBeInTheDocument();
    fireEvent.click(chip);
    expect(onExpand).toHaveBeenCalledTimes(1);
  });

  it("hides the count at zero and labels offline", () => {
    render(() => <MiniChip pulse="disconnected" unseen={0} onExpand={() => {}} />);
    expect(screen.getByText("offline")).toBeInTheDocument();
    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });

  it("uses the same word in the accessible name as the visible label", () => {
    render(() => <MiniChip pulse="disconnected" unseen={0} onExpand={() => {}} />);
    expect(screen.getByRole("button", { name: "Black Box companion: offline, 0 unseen" })).toBeInTheDocument();
  });

  it("reports its own rendered width on mount so the shell can size the panel to fit", () => {
    const onSize = vi.fn();
    render(() => <MiniChip pulse="connecting" unseen={42} onExpand={() => {}} onSize={onSize} />);
    expect(onSize).toHaveBeenCalledTimes(1);
    expect(onSize).toHaveBeenCalledWith(expect.any(Number));
  });
});
