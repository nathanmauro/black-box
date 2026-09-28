import Foundation

/// Pure readiness check used by `--self-test`, kept apart from the AppKit/WebKit orchestration so it
/// can be unit tested. `.companion` mounts synchronously before its data loads, so checking for that
/// element alone lets a page stuck behind a failed API call, or one that never wired the bridge,
/// report "ok".
enum SelfTestChecks {
    static func isReady(mode: String?, hasError: Bool, pulse: String?, receivedBridgeMessage: Bool) -> Bool {
        guard let mode, !mode.isEmpty else { return false }
        if hasError { return false }
        guard let pulse, pulse != "connecting" else { return false }
        return receivedBridgeMessage
    }
}
