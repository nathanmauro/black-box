import XCTest
@testable import BlackBoxCompanion

private final class FakePanelSizeStore: PanelSizeStore {
    private var sizes: [String: CGSize] = [:]
    private(set) var setCalls: [(mode: String, size: CGSize)] = []

    func size(forMode mode: String) -> CGSize? { sizes[mode] }

    func setSize(_ size: CGSize, forMode mode: String) {
        sizes[mode] = size
        setCalls.append((mode, size))
    }
}

final class PanelSizeMemoryTests: XCTestCase {
    func testUsesThePageDefaultWhenNothingIsRemembered() {
        let store = FakePanelSizeStore()
        let size = PanelSizeMemory.resolvedSize(mode: "expanded", pageDefault: CGSize(width: 400, height: 560), store: store)
        XCTAssertEqual(size, CGSize(width: 400, height: 560))
    }

    func testPrefersARememberedManualSizeOverThePageDefault() {
        let store = FakePanelSizeStore()
        store.setSize(CGSize(width: 620, height: 700), forMode: "expanded")
        let size = PanelSizeMemory.resolvedSize(mode: "expanded", pageDefault: CGSize(width: 400, height: 560), store: store)
        XCTAssertEqual(size, CGSize(width: 620, height: 700))
    }

    func testRememberedSizesAreKeyedPerMode() {
        let store = FakePanelSizeStore()
        store.setSize(CGSize(width: 620, height: 700), forMode: "expanded")
        let size = PanelSizeMemory.resolvedSize(mode: "compact", pageDefault: CGSize(width: 340, height: 420), store: store)
        XCTAssertEqual(size, CGSize(width: 340, height: 420))
    }

    func testRecordsAManualResize() {
        let store = FakePanelSizeStore()
        PanelSizeMemory.recordResize(CGSize(width: 500, height: 600), forMode: "expanded", programmatic: false, store: store)
        XCTAssertEqual(store.setCalls.count, 1)
        XCTAssertEqual(store.size(forMode: "expanded"), CGSize(width: 500, height: 600))
    }

    func testAProgrammaticResizeIsNeverRecorded() {
        let store = FakePanelSizeStore()
        store.setSize(CGSize(width: 500, height: 600), forMode: "expanded")
        let callsBefore = store.setCalls.count
        PanelSizeMemory.recordResize(CGSize(width: 400, height: 560), forMode: "expanded", programmatic: true, store: store)
        XCTAssertEqual(store.setCalls.count, callsBefore)
        XCTAssertEqual(store.size(forMode: "expanded"), CGSize(width: 500, height: 600))
    }
}
