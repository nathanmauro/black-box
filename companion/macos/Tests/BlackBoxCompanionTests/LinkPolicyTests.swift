import XCTest
@testable import BlackBoxCompanion

final class LinkPolicyTests: XCTestCase {
    let origin = URL(string: "http://127.0.0.1:8766/companion?embedded=1")!

    func testAllowsOnlyHttpAndHttps() {
        XCTAssertTrue(LinkPolicy.isAllowedScheme(URL(string: "https://example.com")))
        XCTAssertTrue(LinkPolicy.isAllowedScheme(URL(string: "http://example.com")))
        XCTAssertFalse(LinkPolicy.isAllowedScheme(URL(string: "mailto:a@example.com")))
        XCTAssertFalse(LinkPolicy.isAllowedScheme(URL(string: "file:///etc/passwd")))
        XCTAssertFalse(LinkPolicy.isAllowedScheme(URL(string: "javascript:alert(1)")))
        XCTAssertFalse(LinkPolicy.isAllowedScheme(nil))
    }

    func testSameOriginNavigationIsNotExternal() {
        let sameOrigin = URL(string: "http://127.0.0.1:8766/?view=browse&session=s1&event=e1")!
        XCTAssertFalse(LinkPolicy.isExternalNavigation(to: sameOrigin, from: origin))
    }

    func testDifferentHostSchemeOrPortIsExternal() {
        XCTAssertTrue(LinkPolicy.isExternalNavigation(to: URL(string: "https://example.com/x"), from: origin))
        XCTAssertTrue(LinkPolicy.isExternalNavigation(to: URL(string: "http://127.0.0.1:9999/x"), from: origin))
        XCTAssertTrue(LinkPolicy.isExternalNavigation(to: URL(string: "https://127.0.0.1:8766/x"), from: origin))
    }

    func testNilUrlIsNotExternal() {
        XCTAssertFalse(LinkPolicy.isExternalNavigation(to: nil, from: origin))
    }
}
