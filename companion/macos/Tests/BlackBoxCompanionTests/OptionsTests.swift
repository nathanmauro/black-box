import XCTest
@testable import BlackBoxCompanion

final class OptionsTests: XCTestCase {
    func testDefaultsToLocalCompanionRoute() {
        let options = Options.parse([], env: [:])
        XCTAssertEqual(options.url.absoluteString, "http://127.0.0.1:8766/companion?embedded=1")
        XCTAssertNil(options.selfTestOutput)
    }

    func testEnvironmentOverridesDefaultAndFlagOverridesEnvironment() {
        let fromEnv = Options.parse([], env: ["BLACKBOX_COMPANION_URL": "http://127.0.0.1:8799/companion"])
        XCTAssertEqual(fromEnv.url.absoluteString, "http://127.0.0.1:8799/companion")
        let fromFlag = Options.parse(["--url", "http://localhost:9/x"], env: ["BLACKBOX_COMPANION_URL": "http://127.0.0.1:8799/companion"])
        XCTAssertEqual(fromFlag.url.absoluteString, "http://localhost:9/x")
    }

    func testSelfTestFlagCapturesOutputPath() {
        let options = Options.parse(["--self-test", "/tmp/shot.png"], env: [:])
        XCTAssertEqual(options.selfTestOutput, "/tmp/shot.png")
    }

    func testInvalidUrlFallsBackToDefault() {
        let options = Options.parse(["--url", "not a url"], env: [:])
        XCTAssertEqual(options.url.absoluteString, "http://127.0.0.1:8766/companion?embedded=1")
    }
}
