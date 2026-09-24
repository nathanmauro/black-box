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

    // Spec 3.3/3.4: only Expanded is user-resizable; mini and compact always follow the page's own
    // measured size.
    func testDoesNotRecordAManualResizeForMiniOrCompact() {
        let store = FakePanelSizeStore()
        PanelSizeMemory.recordResize(CGSize(width: 200, height: 40), forMode: "mini", programmatic: false, store: store)
        PanelSizeMemory.recordResize(CGSize(width: 380, height: 440), forMode: "compact", programmatic: false, store: store)
        XCTAssertEqual(store.setCalls.count, 0)
    }

    func testIgnoresARememberedSizeForMiniOrCompactEvenIfOnePersistsFromOlderData() {
        let store = FakePanelSizeStore()
        store.setSize(CGSize(width: 200, height: 40), forMode: "mini")
        let size = PanelSizeMemory.resolvedSize(mode: "mini", pageDefault: CGSize(width: 132, height: 36), store: store)
        XCTAssertEqual(size, CGSize(width: 132, height: 36))
    }
}
