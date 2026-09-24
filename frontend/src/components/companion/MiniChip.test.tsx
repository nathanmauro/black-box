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
});
