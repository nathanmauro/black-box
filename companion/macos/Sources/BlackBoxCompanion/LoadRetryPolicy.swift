import Foundation

/// Capped exponential backoff for reloading the companion page after a failed load or a WebContent
/// process termination, so the shell keeps retrying (2s, 4s, 8s, 16s, 30s, 30s, …) instead of giving
/// up after a single failed attempt or hammering the server.
enum LoadRetryPolicy {
    static func delay(forAttempt attempt: Int, initial: TimeInterval = 2, cap: TimeInterval = 30) -> TimeInterval {
        guard attempt > 0 else { return initial }
        return min(initial * pow(2, Double(attempt)), cap)
    }
}
