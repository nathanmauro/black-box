import WebKit
import XCTest
@testable import BlackBoxCompanion

final class DraggableWebViewTests: XCTestCase {
    // Plain WKWebView.mouseDownCanMoveWindow is false (WebKit handles its own mouse-down), which is
    // exactly why isMovableByWindowBackground has no effect through it — confirms the premise this
    // override exists to fix.
    func testPlainWebViewCannotMoveTheWindowOnMouseDown() {
        let plain = WKWebView(frame: NSRect(x: 0, y: 0, width: 100, height: 100))
        XCTAssertFalse(plain.mouseDownCanMoveWindow)
    }

    func testDraggableWebViewCanMoveTheWindowOnMouseDown() {
        let draggable = DraggableWebView(frame: NSRect(x: 0, y: 0, width: 100, height: 100))
        XCTAssertTrue(draggable.mouseDownCanMoveWindow)
    }
}
