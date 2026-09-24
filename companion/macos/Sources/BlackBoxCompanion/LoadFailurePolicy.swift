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

    /// True for a navigation error that is the shell cancelling its own in-flight load on purpose
    /// (reloadNow() superseding a load already in progress — a manual Reload, or the capped retry
    /// firing again before a slow prior attempt settled) rather than a real failure. WebKit reports
    /// that as NSURLErrorCancelled; treating it as a failure would flip the menubar to disconnected
    /// and schedule a redundant retry for a load the shell itself chose to abandon.
    static func isIgnorableError(_ error: Error) -> Bool {
        let ns = error as NSError
        return ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled
    }
}
