import Foundation

/// Pure classification for the shell's load-failure handling. WebKit treats an HTTP error response
/// (a 500 for /companion, or mid `mvn package` / `launchctl kickstart` restart) as a *successful*
/// navigation — `didFinish` fires, not `didFail` — so the response status has to be checked
/// explicitly in `decidePolicyFor navigationResponse` instead.
enum LoadFailurePolicy {
    /// True when `statusCode` is a non-2xx response to the main frame's own request (not a
    /// sub-resource the page loaded itself, which is the page's concern, not the shell's).
    static func isFailureResponse(statusCode: Int, isMainFrame: Bool) -> Bool {
        isMainFrame && !(200...299).contains(statusCode)
    }
}
