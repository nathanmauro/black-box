import XCTest
@testable import BlackBoxCompanion

final class LoadRetryPolicyTests: XCTestCase {
    func testStartsAtTheInitialDelay() {
        XCTAssertEqual(LoadRetryPolicy.delay(forAttempt: 0), 2)
    }

    func testDoublesEachAttempt() {
        XCTAssertEqual(LoadRetryPolicy.delay(forAttempt: 1), 4)
        XCTAssertEqual(LoadRetryPolicy.delay(forAttempt: 2), 8)
        XCTAssertEqual(LoadRetryPolicy.delay(forAttempt: 3), 16)
    }

    func testCapsAtTheMaximumInsteadOfGrowingUnbounded() {
        XCTAssertEqual(LoadRetryPolicy.delay(forAttempt: 4), 30)
        XCTAssertEqual(LoadRetryPolicy.delay(forAttempt: 20), 30)
    }

    func testNeverReturnsZeroOrNegativeForAnAttemptBelowZero() {
        XCTAssertEqual(LoadRetryPolicy.delay(forAttempt: -1), 2)
    }
}
