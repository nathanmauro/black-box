import XCTest
@testable import BlackBoxCompanion

final class BridgeMessageTests: XCTestCase {
    func testParsesStateMessage() {
        let body: [String: Any] = ["type": "state", "pulse": "live", "unseen": 2]
        XCTAssertEqual(BridgeMessage.parse(body), .state(pulse: .live, unseen: 2))
    }

    func testParsesModeMessage() {
        let body: [String: Any] = ["type": "mode", "mode": "compact", "width": 340, "height": 420.0]
        XCTAssertEqual(BridgeMessage.parse(body), .mode(name: "compact", width: 340, height: 420))
    }

    func testRejectsGarbage() {
        XCTAssertNil(BridgeMessage.parse("nope"))
        XCTAssertNil(BridgeMessage.parse(["type": "state", "pulse": "weird", "unseen": 1]))
        XCTAssertNil(BridgeMessage.parse(["type": "mode", "mode": "compact"]))
    }
}
