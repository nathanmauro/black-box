/// Tracks whether the page has proven itself alive — sent the bridge `state` message — since its
/// most recent load. A load can `didFinish` (a 2xx main-frame response) while never actually
/// rendering (a broken JS bundle, a blank error page from a proxy) and so never call the bridge;
/// this lets a caller schedule a grace-period check that only treats that as a failure while no
/// `state` message has arrived for the *current* load.
struct PageReadinessTracker {
    private var receivedStateSinceLoad = false

    /// True until `receivedState()` is called again for the load started by the most recent
    /// `loadStarted()` — including before any load has ever started.
    var isStale: Bool { !receivedStateSinceLoad }

    mutating func loadStarted() {
        receivedStateSinceLoad = false
    }

    mutating func receivedState() {
        receivedStateSinceLoad = true
    }
}
