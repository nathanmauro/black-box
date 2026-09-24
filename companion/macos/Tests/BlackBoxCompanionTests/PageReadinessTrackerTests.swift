import XCTest
@testable import BlackBoxCompanion

final class PageReadinessTrackerTests: XCTestCase {
    func testIsStaleBeforeAnyLoadHasStarted() {
        let tracker = PageReadinessTracker()
        XCTAssertTrue(tracker.isStale)
    }

    func testIsStaleImmediatelyAfterALoadStarts() {
        var tracker = PageReadinessTracker()
        tracker.loadStarted()
        XCTAssertTrue(tracker.isStale)
    }

    func testIsNoLongerStaleOnceTheBridgeReportsState() {
        var tracker = PageReadinessTracker()
        tracker.loadStarted()
        tracker.receivedState()
        XCTAssertFalse(tracker.isStale)
    }

    func testANewLoadResetsReadinessEvenAfterThePreviousOneSucceeded() {
        var tracker = PageReadinessTracker()
        tracker.loadStarted()
        tracker.receivedState()
        tracker.loadStarted()
        XCTAssertTrue(tracker.isStale)
    }
}
