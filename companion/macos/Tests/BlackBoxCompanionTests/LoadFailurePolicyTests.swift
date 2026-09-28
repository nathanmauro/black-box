import XCTest
@testable import BlackBoxCompanion

final class LoadFailurePolicyTests: XCTestCase {
    func testFlagsANonSuccessStatusOnTheMainFrame() {
        XCTAssertTrue(LoadFailurePolicy.isFailureResponse(statusCode: 500, isMainFrame: true))
        XCTAssertTrue(LoadFailurePolicy.isFailureResponse(statusCode: 404, isMainFrame: true))
        XCTAssertTrue(LoadFailurePolicy.isFailureResponse(statusCode: 301, isMainFrame: true))
    }

    func testAllowsEverySuccessStatusOnTheMainFrame() {
        XCTAssertFalse(LoadFailurePolicy.isFailureResponse(statusCode: 200, isMainFrame: true))
        XCTAssertFalse(LoadFailurePolicy.isFailureResponse(statusCode: 204, isMainFrame: true))
        XCTAssertFalse(LoadFailurePolicy.isFailureResponse(statusCode: 299, isMainFrame: true))
    }

    func testIgnoresAFailureStatusOffTheMainFrame() {
        // A sub-resource (an image, an API call the page itself made) failing is the page's own
        // concern, not a reason to reload the whole shell.
        XCTAssertFalse(LoadFailurePolicy.isFailureResponse(statusCode: 500, isMainFrame: false))
    }

    func testIgnoresACancelledNavigationError() {
        // reloadNow() cancelling a load already in flight (a manual Reload, or the shell's own
        // capped retry firing again) surfaces as NSURLErrorCancelled; that is the shell cancelling
        // itself on purpose, not a real failure.
        let cancelled = NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled)
        XCTAssertTrue(LoadFailurePolicy.isIgnorableError(cancelled))
    }

    func testDoesNotIgnoreOtherNSURLErrors() {
        let timedOut = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)
        XCTAssertFalse(LoadFailurePolicy.isIgnorableError(timedOut))
    }

    func testDoesNotIgnoreAnErrorWithTheSameCodeInADifferentDomain() {
        let lookalike = NSError(domain: "SomeOtherDomain", code: NSURLErrorCancelled)
        XCTAssertFalse(LoadFailurePolicy.isIgnorableError(lookalike))
    }
}
