import XCTest
@testable import BlackBoxCompanion

final class SelfTestChecksTests: XCTestCase {
    func testReadyOnceModeIsPresentNoErrorShowsAndPulseHasSettledAndTheBridgeFired() {
        XCTAssertTrue(SelfTestChecks.isReady(mode: "mini", hasError: false, pulse: "idle", receivedBridgeMessage: true))
    }

    func testNotReadyWithoutTheCompanionElement() {
        XCTAssertFalse(SelfTestChecks.isReady(mode: nil, hasError: false, pulse: "idle", receivedBridgeMessage: true))
        XCTAssertFalse(SelfTestChecks.isReady(mode: "", hasError: false, pulse: "idle", receivedBridgeMessage: true))
    }

    // A server that serves the SPA shell but whose API calls fail (wrong port, half-restarted jar)
    // still renders .companion; the self-test must not report ok for that.
    func testNotReadyWhileTheErrorBannerShows() {
        XCTAssertFalse(SelfTestChecks.isReady(mode: "mini", hasError: true, pulse: "idle", receivedBridgeMessage: true))
    }

    func testNotReadyWhileStillConnecting() {
        XCTAssertFalse(SelfTestChecks.isReady(mode: "mini", hasError: false, pulse: "connecting", receivedBridgeMessage: true))
        XCTAssertFalse(SelfTestChecks.isReady(mode: "mini", hasError: false, pulse: nil, receivedBridgeMessage: true))
    }

    // The state/mode bridge is the shell's only real integration point; a self-test that never
    // registers the "companion" message handler never actually exercises it.
    func testNotReadyBeforeTheBridgeHasDeliveredAMessage() {
        XCTAssertFalse(SelfTestChecks.isReady(mode: "mini", hasError: false, pulse: "idle", receivedBridgeMessage: false))
    }
}
