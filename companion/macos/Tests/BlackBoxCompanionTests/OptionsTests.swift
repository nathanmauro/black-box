import XCTest
@testable import BlackBoxCompanion

final class OptionsTests: XCTestCase {
    func testDefaultsToLocalCompanionRoute() throws {
        let options = try Options.parse([], env: [:])
        XCTAssertEqual(options.url.absoluteString, "http://127.0.0.1:8766/companion?embedded=1")
        XCTAssertNil(options.selfTestOutput)
        XCTAssertNil(options.clickThroughOutput)
    }

    func testEnvironmentOverridesDefaultAndFlagOverridesEnvironment() throws {
        let fromEnv = try Options.parse([], env: ["BLACKBOX_COMPANION_URL": "http://127.0.0.1:8799/companion"])
        XCTAssertEqual(fromEnv.url.absoluteString, "http://127.0.0.1:8799/companion")
        let fromFlag = try Options.parse(["--url", "http://localhost:9/x"], env: ["BLACKBOX_COMPANION_URL": "http://127.0.0.1:8799/companion"])
        XCTAssertEqual(fromFlag.url.absoluteString, "http://localhost:9/x")
    }

    func testSelfTestFlagCapturesOutputPath() throws {
        let options = try Options.parse(["--self-test", "/tmp/shot.png"], env: [:])
        XCTAssertEqual(options.selfTestOutput, "/tmp/shot.png")
    }

    func testClickThroughFlagCapturesOutputDirectory() throws {
        let options = try Options.parse(["--click-through", "/tmp/click", "--url", "http://127.0.0.1:8797/companion?embedded=1"], env: [:])
        XCTAssertEqual(options.clickThroughOutput, "/tmp/click")
        XCTAssertNil(options.selfTestOutput)
        XCTAssertEqual(options.url.absoluteString, "http://127.0.0.1:8797/companion?embedded=1")
    }

    // A silent fallback to the production default here would mean a mistyped --url during the
    // documented e2e workflow (self-test against an isolated port) silently tests the live service.
    func testInvalidUrlFailsInsteadOfFallingBackToDefault() {
        XCTAssertThrowsError(try Options.parse(["--url", "not a url"], env: [:])) { error in
            guard case Options.ParseError.invalidValue(let flag, let value) = error else {
                return XCTFail("expected an invalidValue error, got \(error)")
            }
            XCTAssertEqual(flag, "--url")
            XCTAssertEqual(value, "not a url")
        }
    }

    func testInvalidEnvironmentUrlFailsInsteadOfFallingBackToDefault() {
        XCTAssertThrowsError(try Options.parse([], env: ["BLACKBOX_COMPANION_URL": "not a url"])) { error in
            guard case Options.ParseError.invalidValue(let flag, _) = error else {
                return XCTFail("expected an invalidValue error, got \(error)")
            }
            XCTAssertEqual(flag, "BLACKBOX_COMPANION_URL")
        }
    }

    // A missing value silently ran the full GUI app instead of the self-test a CI/e2e caller was
    // waiting to exit; a flag-shaped value (the next flag, consumed as if it were this flag's value)
    // silently ran against whatever URL was left in place and wrote its output to a file named after
    // that flag.
    func testSelfTestWithNoValueFails() {
        XCTAssertThrowsError(try Options.parse(["--self-test"], env: [:])) { error in
            guard case Options.ParseError.missingValue(let flag) = error else {
                return XCTFail("expected a missingValue error, got \(error)")
            }
            XCTAssertEqual(flag, "--self-test")
        }
    }

    func testSelfTestFollowedByAnotherFlagFails() {
        XCTAssertThrowsError(try Options.parse(["--self-test", "--url", "http://127.0.0.1:8799/companion"], env: [:])) { error in
            guard case Options.ParseError.missingValue(let flag) = error else {
                return XCTFail("expected a missingValue error, got \(error)")
            }
            XCTAssertEqual(flag, "--self-test")
        }
    }

    func testUrlWithNoValueFails() {
        XCTAssertThrowsError(try Options.parse(["--url"], env: [:])) { error in
            guard case Options.ParseError.missingValue(let flag) = error else {
                return XCTFail("expected a missingValue error, got \(error)")
            }
            XCTAssertEqual(flag, "--url")
        }
    }

    func testClickThroughWithNoValueFails() {
        XCTAssertThrowsError(try Options.parse(["--click-through"], env: [:])) { error in
            guard case Options.ParseError.missingValue(let flag) = error else {
                return XCTFail("expected a missingValue error, got \(error)")
            }
            XCTAssertEqual(flag, "--click-through")
        }
    }
}
