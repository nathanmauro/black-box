import XCTest
@testable import BlackBoxCompanion

private final class SupportingTarget: NSObject {
    @objc dynamic var drawsBackground: Bool = true
}

private final class UnsupportingTarget: NSObject {}

final class WebViewTransparencyTests: XCTestCase {
    func testAppliesWhenTheKeyIsSupported() {
        let target = SupportingTarget()
        WebViewTransparency.apply(to: target)
        XCTAssertFalse(target.drawsBackground)
    }

    func testSkipsSilentlyWhenTheKeyIsUnsupported() {
        // Must not throw/crash (NSUnknownKeyException) when the private key is unavailable.
        let target = UnsupportingTarget()
        WebViewTransparency.apply(to: target)
    }
}
