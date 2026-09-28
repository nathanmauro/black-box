import XCTest
@testable import BlackBoxCompanion

final class StatusTitleTests: XCTestCase {
    func testGlyphPerPulse() {
        XCTAssertEqual(StatusTitle.render(pulse: .live, unseen: 0), "●")
        XCTAssertEqual(StatusTitle.render(pulse: .idle, unseen: 0), "○")
        XCTAssertEqual(StatusTitle.render(pulse: .connecting, unseen: 0), "◌")
        XCTAssertEqual(StatusTitle.render(pulse: .disconnected, unseen: 0), "◌")
    }

    func testAppendsUnseenCountWhenPositive() {
        XCTAssertEqual(StatusTitle.render(pulse: .live, unseen: 3), "● 3")
        XCTAssertEqual(StatusTitle.render(pulse: .idle, unseen: -1), "○")
    }
}
