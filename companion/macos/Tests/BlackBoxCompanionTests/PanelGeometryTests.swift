import XCTest
@testable import BlackBoxCompanion

final class PanelGeometryTests: XCTestCase {
    let screen = CGRect(x: 0, y: 0, width: 1440, height: 900)

    func testResizeKeepsTopRightCornerAnchored() {
        let current = CGRect(x: 1000, y: 500, width: 132, height: 36)
        let next = PanelGeometry.frame(resizing: current, to: CGSize(width: 340, height: 420), within: screen)
        XCTAssertEqual(next, CGRect(x: 792, y: 116, width: 340, height: 420))
    }

    func testClampsSizeAndPosition() {
        let current = CGRect(x: 10, y: 10, width: 132, height: 36)
        let tiny = PanelGeometry.frame(resizing: current, to: CGSize(width: -5, height: 0), within: screen)
        XCTAssertEqual(tiny.size, CGSize(width: 100, height: 28))
        let huge = PanelGeometry.frame(resizing: current, to: CGSize(width: 9999, height: 9999), within: screen)
        XCTAssertEqual(huge.size, CGSize(width: 1440, height: 900))
        XCTAssertEqual(huge.origin, CGPoint(x: 0, y: 0))
        let offscreen = PanelGeometry.frame(resizing: CGRect(x: 1400, y: 5, width: 132, height: 36), to: CGSize(width: 400, height: 560), within: screen)
        XCTAssertGreaterThanOrEqual(offscreen.minX, screen.minX)
        XCTAssertGreaterThanOrEqual(offscreen.minY, screen.minY)
        XCTAssertLessThanOrEqual(offscreen.maxX, screen.maxX)
        XCTAssertLessThanOrEqual(offscreen.maxY, screen.maxY)
    }
}
