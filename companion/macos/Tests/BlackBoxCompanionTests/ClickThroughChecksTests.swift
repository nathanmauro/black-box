import XCTest
@testable import BlackBoxCompanion

final class ClickThroughChecksTests: XCTestCase {
    let companion = URL(string: "http://127.0.0.1:8797/companion?embedded=1")!

    func testAcceptsABrowseDeepLinkOnTheCompanionOrigin() {
        let url = URL(string: "http://127.0.0.1:8797/?view=browse&session=s-1&event=e-2&project=p")
        XCTAssertNil(ClickThroughChecks.deepLinkProblem(url, companion: companion))
    }

    func testRejectsAMissingUrl() {
        XCTAssertNotNil(ClickThroughChecks.deepLinkProblem(nil, companion: companion))
    }

    func testRejectsAnotherOrigin() {
        let otherPort = URL(string: "http://127.0.0.1:8766/?view=browse&session=s&event=e")
        XCTAssertNotNil(ClickThroughChecks.deepLinkProblem(otherPort, companion: companion))
        let otherHost = URL(string: "http://example.com:8797/?view=browse&session=s&event=e")
        XCTAssertNotNil(ClickThroughChecks.deepLinkProblem(otherHost, companion: companion))
    }

    func testRejectsAPathOtherThanRoot() {
        let url = URL(string: "http://127.0.0.1:8797/companion?view=browse&session=s&event=e")
        XCTAssertNotNil(ClickThroughChecks.deepLinkProblem(url, companion: companion))
    }

    func testRejectsAMissingOrEmptyQueryItem() {
        XCTAssertNotNil(ClickThroughChecks.deepLinkProblem(URL(string: "http://127.0.0.1:8797/?view=stream&session=s&event=e"), companion: companion))
        XCTAssertNotNil(ClickThroughChecks.deepLinkProblem(URL(string: "http://127.0.0.1:8797/?view=browse&event=e"), companion: companion))
        XCTAssertNotNil(ClickThroughChecks.deepLinkProblem(URL(string: "http://127.0.0.1:8797/?view=browse&session=s&event="), companion: companion))
    }

    func testSizeMatchAllowsSubPointRounding() {
        XCTAssertTrue(ClickThroughChecks.sizeMatches(CGSize(width: 340, height: 420), CGSize(width: 340, height: 420)))
        XCTAssertTrue(ClickThroughChecks.sizeMatches(CGSize(width: 340.5, height: 419.5), CGSize(width: 340, height: 420)))
        XCTAssertFalse(ClickThroughChecks.sizeMatches(CGSize(width: 132, height: 36), CGSize(width: 340, height: 420)))
        XCTAssertFalse(ClickThroughChecks.sizeMatches(CGSize(width: 342, height: 420), CGSize(width: 340, height: 420)))
    }

    func testReadsThePageShellStateFromTheChip() {
        let live = ClickThroughChecks.chipState(className: "companion-chip companion-chip--live", countText: "3")
        XCTAssertEqual(live?.pulse, .live)
        XCTAssertEqual(live?.unseen, 3)
        let offline = ClickThroughChecks.chipState(className: "companion-chip companion-chip--disconnected", countText: nil)
        XCTAssertEqual(offline?.pulse, .disconnected)
        XCTAssertEqual(offline?.unseen, 0)
        XCTAssertNil(ClickThroughChecks.chipState(className: "companion-chip", countText: nil))
        XCTAssertNil(ClickThroughChecks.chipState(className: "companion-chip companion-chip--bogus", countText: "1"))
    }

    func testExpectedStatusTitleMatchesTheShellRendering() {
        let state = ClickThroughChecks.chipState(className: "companion-chip companion-chip--live", countText: "2")!
        XCTAssertEqual(ClickThroughChecks.expectedStatusTitle(state), StatusTitle.render(pulse: .live, unseen: 2))
    }
}
