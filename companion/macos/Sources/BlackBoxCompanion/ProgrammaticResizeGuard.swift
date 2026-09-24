/// Tracks overlapping programmatic (bridge mode message) panel resizes, so the `didResize`
/// notifications they trigger are correctly ignored by `panelDidResize` for as long as *any* of
/// them is still animating. A single boolean cleared by one 0.3s timer per call is not enough: two
/// mode messages within the grace period (e.g. a mini width remeasure right after a mode change)
/// can have the first call's timer clear the guard while the second call's animation is still
/// emitting resize notifications, recording an intermediate frame as a manual resize.
struct ProgrammaticResizeGuard {
    private var inFlightCount = 0

    var isActive: Bool { inFlightCount > 0 }

    /// Call when a programmatic resize starts (its `setFrame(animate:)` is issued).
    mutating func begin() {
        inFlightCount += 1
    }

    /// Call when that same resize's grace period elapses. Never goes negative, so a stray `end()`
    /// cannot make the guard falsely report inactive while another resize is still in flight.
    mutating func end() {
        inFlightCount = max(0, inFlightCount - 1)
    }
}
