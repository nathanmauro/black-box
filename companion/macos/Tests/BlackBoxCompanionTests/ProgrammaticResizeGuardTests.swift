import XCTest
@testable import BlackBoxCompanion

final class ProgrammaticResizeGuardTests: XCTestCase {
    func testIsInactiveInitially() {
        XCTAssertFalse(ProgrammaticResizeGuard().isActive)
    }

    func testIsActiveWhileOneResizeIsInFlight() {
        var guarded = ProgrammaticResizeGuard()
        guarded.begin()
        XCTAssertTrue(guarded.isActive)
    }

    func testStaysActiveWhenASecondResizeOverlapsTheFirstEndingFirst() {
        // Two mode messages within the grace period (e.g. a mini width remeasure right after a mode
        // change): the first's timer must not clear the guard while the second's animation is still
        // emitting didResize notifications.
        var guarded = ProgrammaticResizeGuard()
        guarded.begin()
        guarded.begin()
        guarded.end()
        XCTAssertTrue(guarded.isActive)
    }

    func testBecomesInactiveOnceEveryInFlightResizeHasEnded() {
        var guarded = ProgrammaticResizeGuard()
        guarded.begin()
        guarded.begin()
        guarded.end()
        guarded.end()
        XCTAssertFalse(guarded.isActive)
    }

    func testEndIsSafeWithoutAMatchingBegin() {
        var guarded = ProgrammaticResizeGuard()
        guarded.end()
        XCTAssertFalse(guarded.isActive)
    }
}
